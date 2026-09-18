package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.dao.ConversationPreviewPatch
import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.repository.SidebarRepository
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

class InternalSidebarRepository(
    private val clientApi: ClientApi,
    private val conversationDao: ConversationDao,
    private val userDao: UserDao,
    private val currentUserId: () -> String = { "" },
) : SidebarRepository {

    private val sections = MutableStateFlow<ChannelSectionConfig?>(null)
    private val refreshMutex = Mutex()

    override suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val channelsResult = runCatching {
                val channels = clientApi.channels(returnAllRelevantMpdms = true).unwrap()
                val body = channels.channels
                val rawRoster = body?.channels.orEmpty() + body?.ims.orEmpty() + body?.mpims.orEmpty()
                val roster = rawRoster.mapNotNull { it.decodeClientConversation(System.currentTimeMillis()) }
                conversationDao.upsertRoster(roster, rosterOpenStates(rawRoster, body?.isOpen))
                channels.sections?.let { supplied ->
                    val names = conversationDao.namesById().map { it.id }
                    val cached = names.chunked(900).flatMap { conversationDao.findByIds(it) }
                    sections.value = decodeSections(supplied, cached)
                }
            }.onFailure { if (it is CancellationException) throw it }
            val dmsResult = runCatching {

                val names = MentionNames(
                    currentUserId = currentUserId(),
                    displayNames = userDao.allNames().associate { it.id to it.displayName },
                )
                conversationDao.updateDirectPreviews(parseDmPreviews(clientApi.dms(count = DM_COUNT), names))
            }.onFailure { if (it is CancellationException) throw it }
            val failures = listOfNotNull(channelsResult.exceptionOrNull(), dmsResult.exceptionOrNull())
            if (failures.isEmpty()) Result.success(Unit)
            else Result.failure(IllegalStateException("Sidebar refresh failed", failures.first()).apply {
                failures.drop(1).forEach(::addSuppressed)
            })
        }
    }

    override suspend fun sections(): Result<ChannelSectionConfig> = runCatching {
        checkNotNull(sections.value) { "Sidebar sections have not been supplied" }
    }

    private companion object {
        const val DM_COUNT = 200L
    }
}

internal fun rosterOpenStates(rows: List<JsonObject>, openIds: List<String>?): Map<String, Boolean> {
    val open = openIds?.toSet()
    return buildMap {
        rows.forEach { row ->
            val id = (row["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@forEach
            val explicit = (row["is_open"] as? JsonPrimitive)?.booleanOrNull
            when {
                explicit != null -> put(id, explicit)
                open != null -> put(id, id in open)
            }
        }
        open?.forEach { put(it, true) }
    }
}

private fun parseDmPreviews(body: JsonObject, names: MentionNames): List<ConversationPreviewPatch> {
    check((body["ok"] as? JsonPrimitive)?.booleanOrNull != false) { "client.dms failed" }
    return listOf("ims", "mpims").flatMap { key ->
        (body[key] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val message = row["message"] as? JsonObject ?: return@mapNotNull null
            val ts = (message["ts"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val text = (message["text"] as? JsonPrimitive)?.contentOrNull
            ConversationPreviewPatch(id, text?.let { flatten(it, names) }, ts)
        }
    }
}
