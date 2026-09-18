package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.MessageDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.Message
import com.scooter.slackwear.core.model.SlackUser
import com.scooter.slackwear.core.model.repository.MessageRepository
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.unwrap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.scooter.slackwear.core.network.model.ReactionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import com.scooter.slackwear.core.model.PaginationState
import com.scooter.slackwear.core.network.toNetworkFailure
import com.scooter.slackwear.core.network.model.MessageDto
import java.util.concurrent.ConcurrentHashMap

class SlackMessageRepository(
    private val api: SlackApi,
    private val uploadClient: OkHttpClient,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val currentUserId: () -> String,
) : MessageRepository {

    private val reactionMutex = Mutex()
    private val reactionJson = Json { ignoreUnknownKeys = true }

    private data class PageKey(val userId: String, val conversationId: String, val threadTs: String?)
    private class Pages {
        val mutex = Mutex()
        val state = MutableStateFlow(PaginationState())
        var cursor: String? = null
        val consumed = mutableSetOf<String>()
    }
    private val pages = ConcurrentHashMap<PageKey, Pages>()
    private val metadata = MutableStateFlow<Map<Triple<String, String, String>, MessageDto>>(emptyMap())

    private fun pages(conversationId: String, threadTs: String? = null): Pages =
        pages.getOrPut(PageKey(currentUserId(), conversationId, threadTs)) { Pages() }

    private fun MessageEntity.withMetadata(): Message = toDomain(currentUserId()).withThreadMetadata(
        metadata.value[Triple(currentUserId(), conversationId, ts)],
    )

    override suspend fun findMessage(conversationId: String, ts: String): Message? =
        messageDao.find(conversationId, ts)?.withMetadata()

    override suspend fun lookupMessage(conversationId: String, ts: String): Result<Message?> =
        withContext(Dispatchers.IO) {
            messageDao.find(conversationId, ts)?.let { return@withContext Result.success(it.withMetadata()) }
            runCatching {
                val response = api.conversationHistory(
                    channel = conversationId,
                    limit = 1,
                    latest = ts,
                    inclusive = true,
                ).unwrap()
                val dto = response.messages.firstOrNull { it.ts == ts } ?: return@runCatching null
                val mentioned = mentionedUserIds(dto.text)
                resolveAuthors(listOfNotNull(dto.user) + mentioned)
                currentCoroutineContext().ensureActive()
                val entity = dto.toEntity(conversationId, mentionNames(mentioned))
                messageDao.upsert(listOf(entity))
                entity.withMetadata()
            }.onFailure { if (it is CancellationException) throw it }
        }

    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        combine(messageDao.observeConversation(conversationId), metadata) { messages, _ ->
            messages.map { it.withMetadata() }
        }

    override fun observeThread(conversationId: String, threadTs: String): Flow<List<Message>> =
        combine(messageDao.observeThread(conversationId, threadTs), metadata) { messages, _ ->
            messages.map { it.withMetadata() }
        }

    override fun observeAuthors(): Flow<Map<String, SlackUser>> =
        userDao.observeAll().map { users -> users.associate { it.id to it.toDomain() } }

    override fun historyPagination(conversationId: String): StateFlow<PaginationState> =
        pages(conversationId).state.asStateFlow()

    override fun threadPagination(conversationId: String, threadTs: String): StateFlow<PaginationState> =
        pages(conversationId, threadTs).state.asStateFlow()

    override suspend fun loadHistory(conversationId: String): Result<Unit> =
        loadPage(conversationId, null, initial = true)

    override suspend fun loadThread(conversationId: String, threadTs: String): Result<Unit> =
        loadPage(conversationId, threadTs, initial = true)

    override suspend fun loadOlderHistory(conversationId: String): Result<Unit> =
        loadPage(conversationId, null, initial = false)

    override suspend fun loadMoreReplies(conversationId: String, threadTs: String): Result<Unit> =
        loadPage(conversationId, threadTs, initial = false)

    private suspend fun loadPage(conversationId: String, threadTs: String?, initial: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val userId = currentUserId()
            val page = pages(conversationId, threadTs)
            if (!page.mutex.tryLock()) return@withContext Result.failure(IllegalStateException("Page already loading"))
            val previous = page.state.value
            try {
                if (!initial && previous.endReached) return@withContext Result.success(Unit)
                val first = initial || !previous.initialized
                val cursor = if (first) null else page.cursor
                page.state.value = previous.copy(isLoading = true, error = null)
                val response = if (threadTs == null) {
                    api.conversationHistory(conversationId, limit = PAGE_SIZE, cursor = cursor)
                } else {
                    api.conversationReplies(conversationId, threadTs, limit = REPLY_PAGE_SIZE, cursor = cursor)
                }.unwrap()
                currentCoroutineContext().ensureActive()
                val next = response.responseMetadata?.nextCursor?.trim()?.takeIf(String::isNotEmpty)
                check(next == null || (next != cursor && (first || next !in page.consumed))) {
                    throw IllegalStateException("Slack repeated a pagination cursor")
                }
                check(next != null || !response.hasMore) {
                    throw IllegalStateException("Slack omitted the next pagination cursor")
                }
                val received = response.messages.distinctBy { it.ts }

                val mentioned = received.flatMap { mentionedUserIds(it.text) }
                resolveAuthors(received.mapNotNull { it.user } + mentioned)
                currentCoroutineContext().ensureActive()
                messageDao.upsert(received.map { it.toEntity(conversationId, mentionNames(mentioned)) })
                metadata.update { old ->
                    old + received.associateBy { Triple(userId, conversationId, it.ts) }
                }
                currentCoroutineContext().ensureActive()
                if (first) page.consumed.clear()
                cursor?.let(page.consumed::add)
                page.cursor = next
                page.state.value = PaginationState(
                    initialized = true,
                    endReached = next == null,
                    pagesLoaded = if (first) 1 else previous.pagesLoaded + 1,
                    initialPageTimestamps = if (first) received.map { it.ts }.toSet() else previous.initialPageTimestamps,
                )
                Result.success(Unit)
            } catch (failure: CancellationException) {
                page.state.value = previous
                throw failure
            } catch (failure: Exception) {
                page.state.value = previous.copy(isLoading = false, error = failure.toNetworkFailure())
                Result.failure(failure)
            } finally {
                page.mutex.unlock()
            }
        }

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        threadTs: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {

        val pendingTs = "%.6f".format(java.util.Locale.ROOT, System.currentTimeMillis() / 1000.0)
        val pending = MessageEntity(
            conversationId = conversationId,
            ts = pendingTs,
            authorId = currentUserId(),
            text = text,
            threadTs = threadTs,
            replyCount = 0,
            reactionsJson = "[]",
            isEdited = false,
            deliveryState = DeliveryState.PENDING,
        )
        messageDao.upsert(listOf(pending))

        runCatching {
            val response = api.postMessage(
                channel = conversationId,
                text = text,
                threadTs = threadTs,
            ).unwrap()

            val echoed = response.message
            val mentioned = mentionedUserIds(echoed?.text ?: text)
            if (mentioned.isNotEmpty()) resolveAuthors(mentioned)
            val confirmed = if (echoed != null) {
                echoed.copy(ts = response.ts ?: echoed.ts)
                    .toEntity(conversationId, mentionNames(mentioned))

                    .copy(threadTs = threadTs)
            } else {
                pending.copy(ts = response.ts ?: pendingTs, deliveryState = DeliveryState.SENT)
            }

            messageDao.upsert(listOf(confirmed))
            if (confirmed.ts != pendingTs) messageDao.delete(conversationId, pendingTs)
            Unit
        }.onFailure {
            messageDao.upsert(listOf(pending.copy(deliveryState = DeliveryState.FAILED)))
        }
    }

    override suspend fun toggleReaction(
        conversationId: String,
        ts: String,
        emoji: String,
        add: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            val userId = currentUserId()
            try {
                if (add) {
                    api.addReaction(conversationId, ts, emoji).unwrap()
                } else {
                    api.removeReaction(conversationId, ts, emoji).unwrap()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withLock Result.failure(error)
            }
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { updateLocalReaction(conversationId, ts, emoji, add, userId) }
            }
            Result.success(Unit)
        }
    }

    private suspend fun updateLocalReaction(
        conversationId: String,
        ts: String,
        emoji: String,
        add: Boolean,
        userId: String,
    ) {
        val serializer = ListSerializer(ReactionDto.serializer())
        repeat(5) {
            val message = messageDao.find(conversationId, ts) ?: return
            val reactions = reactionJson.decodeFromString(serializer, message.reactionsJson)
            val existing = reactions.firstOrNull { it.name == emoji }
            val includesMe = existing?.users?.contains(userId) == true
            val users = existing?.users.orEmpty().filterNot { it == userId }.distinct() +
                if (add) listOf(userId) else emptyList()
            val count = ((existing?.count ?: 0) + when {
                add && !includesMe -> 1
                !add && includesMe -> -1
                else -> 0
            }).coerceAtLeast(users.size).coerceAtLeast(0)
            val updated = reactions.filterNot { it.name == emoji } +
                if (count > 0) listOf(ReactionDto(emoji, count, users)) else emptyList()
            if (messageDao.updateReactions(
                    conversationId,
                    ts,
                    message.reactionsJson,
                    reactionJson.encodeToString(serializer, updated),
                ) > 0
            ) return
        }
    }

    override suspend fun sendFile(
        conversationId: String,
        filename: String,
        bytes: ByteArray,
        comment: String?,
        threadTs: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val reserved = api.getUploadUrl(filename = filename, length = bytes.size.toLong()).unwrap()
            val uploadUrl = reserved.uploadUrl
            val fileId = reserved.fileId
            require(uploadUrl != null && fileId != null) { "Slack returned no upload URL" }

            val request = Request.Builder()
                .url(uploadUrl)
                .post(bytes.toRequestBody(contentType = null))
                .build()

            uploadClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Upload failed: HTTP ${response.code}" }
            }

            api.completeUpload(
                filesJson = """[{"id":"$fileId","title":"$filename"}]""",
                channelId = conversationId,
                threadTs = threadTs,
                initialComment = comment?.takeIf(String::isNotBlank),
            ).unwrap()

            loadHistory(conversationId)
            Unit
        }
    }

    private suspend fun mentionNames(ids: List<String>): MentionNames {
        if (ids.isEmpty()) return MentionNames(currentUserId())
        val names = userDao.findByIds(ids.distinct()).associate { it.id to it.displayName }
        return MentionNames(currentUserId(), names)
    }

    private suspend fun resolveAuthors(userIds: List<String>) {
        resolveUsers(
            api = api,
            userDao = userDao,
            ids = userIds,
            maxLookups = MAX_USER_LOOKUPS,
        )
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val REPLY_PAGE_SIZE = 50
        const val MAX_USER_LOOKUPS = 25
    }
}
