package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ActivityDao
import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ConversationRepository
import com.scooter.slackwear.core.model.repository.ConversationSummary
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.UserBootRequest
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private val READ_TIMESTAMP = Regex("[0-9]{1,10}[.][0-9]{6}")

class SlackConversationRepository(
    private val clientApi: ClientApi,
    private val slackApi: SlackApi,
    private val conversationDao: ConversationDao,
    private val activityDao: ActivityDao,
    private val userDao: UserDao,
) : ConversationRepository {

    override fun observeConversations(): Flow<List<ConversationSummary>> =
        combine(
            conversationDao.observeForDisplay(),
            userDao.observeConversationCounterparts(),
        ) { conversations, users ->
            val byId = users.associateBy(UserEntity::id)
            conversations.map { entity ->
                ConversationSummary(
                    conversation = entity.toDomain(),
                    unread = entity.toUnreadState(),
                    latestPreview = entity.latestPreview,
                    counterpart = entity.counterpartUserId?.let { byId[it]?.toDomain() },
                )
            }
        }

            .debounce(EMISSION_DEBOUNCE_MILLIS)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeActivity(): Flow<List<ActivityItem>> =
        combine(
            activityDao.observeRecent(),
            conversationDao.observeForDisplay(),
            userDao.observeConversationCounterparts(),
        ) { mentions, conversations, users ->
            val usersById = users.associateBy(UserEntity::id)
            val counterparts = conversations.mapNotNull { row ->
                row.counterpartUserId?.takeIf(String::isNotBlank)?.let { row.id to it }
            }.toMap()
            val mentionItems = mentions.map {
                it.toActivityItem(usersById.mapValues { (_, user) -> user.toDomain() }, counterparts)
            }

            val unreadDms = conversations
                .filter { it.isDirect }
                .filter { it.unreadCount > 0 || it.mentionCount > 0 }
                .map { entity ->
                    val counterpart = entity.counterpartUserId?.let { usersById[it]?.toDomain() }
                    ActivityItem(
                        id = "unread:${entity.id}",
                        kind = ActivityItem.Kind.DIRECT_MESSAGE,
                        conversationId = entity.id,
                        conversationName = counterpart?.displayName ?: entity.name,
                        author = counterpart ?: SlackUser(
                            id = entity.counterpartUserId ?: entity.id,
                            displayName = entity.name,
                            realName = entity.name,
                            avatarUrl = null,
                        ),
                        preview = entity.latestPreview.orEmpty(),
                        timestamp = entity.latestTs.orEmpty(),
                        unreadCount = maxOf(entity.unreadCount, entity.mentionCount),
                        messageTs = entity.latestTs,
                        unreadCountIsExact = entity.unreadConfidence == com.scooter.slackwear.core.model.UnreadState.Confidence.EXACT,
                    )
                }

            (mentionItems + unreadDms)
                .sortedByDescending { it.timestamp }

        }

            .debounce(EMISSION_DEBOUNCE_MILLIS)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val boot = clientApi.userBoot(UserBootRequest()).unwrap()
            val rawRoster = boot.channels + boot.ims + boot.mpims
            val openStates = rosterOpenStates(rawRoster, boot.isOpen)
            val roster = rawRoster.mapNotNull { it.decodeClientConversation(now) }
                .map { it.copy(isOpen = openStates[it.id] ?: it.isOpen) }
            conversationDao.upsertRoster(roster, openStates)

            val counterpartIds = roster
                .asSequence()
                .filter { it.kind.isDirect() && (it.isOpen || it.unreadCount > 0 || !it.latestTs.isNullOrBlank()) }
                .mapNotNull { it.counterpartUserId }
                .distinct()
                .take(COUNTERPART_RESOLUTION_LIMIT)
                .toList()
            resolutionScope.launch {
                runCatching { resolveUsers(api = slackApi, userDao = userDao, ids = counterpartIds) }
            }
            Unit
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val readMutex = Mutex()
    private val acknowledgedReads = mutableMapOf<String, java.math.BigDecimal>()

    override suspend fun markRead(conversationId: String, ts: String): Result<Unit> = withContext(Dispatchers.IO) {
        readMutex.withLock {
            runCatching {
                require(conversationId.isNotBlank())
                require(READ_TIMESTAMP.matches(ts) && ts.toBigDecimal().signum() > 0)
                val timestamp = ts.toBigDecimal()
                if (acknowledgedReads[conversationId]?.let { it >= timestamp } != true) {
                    slackApi.markConversation(channel = conversationId, ts = ts).unwrap()
                    currentCoroutineContext().ensureActive()
                    conversationDao.acknowledgeRead(conversationId, ts)
                    acknowledgedReads[conversationId] = timestamp
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private companion object {

        const val EMISSION_DEBOUNCE_MILLIS = 150L

        const val COUNTERPART_RESOLUTION_LIMIT = 60
    }
}

private val ConversationEntity.isDirect: Boolean
    get() = kind == ConversationKind.DIRECT_MESSAGE || kind == ConversationKind.GROUP_MESSAGE

private fun ConversationKind.isDirect(): Boolean =
    this == ConversationKind.DIRECT_MESSAGE || this == ConversationKind.GROUP_MESSAGE
