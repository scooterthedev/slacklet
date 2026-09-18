package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.model.repository.HuddleRepository
import com.scooter.slackwear.core.model.repository.HuddleState
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApiException
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive

class InternalHuddleRepository(
    private val clientApi: ClientApi,
    private val userDao: UserDao,
) : HuddleRepository {

    private val huddles = MutableStateFlow<Map<String, HuddleState>>(emptyMap())

    override fun observeHuddle(conversationId: String): Flow<HuddleState?> =
        huddles.map { it[conversationId] }

    override suspend fun refresh(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {

            val huddleId = clientApi.huddleGetIndirect(
                buildJsonObject { put("channel_id", JsonPrimitive(conversationId)) },
            ).requireOk().decodeIndirectHuddleId()

            if (huddleId == null) {
                huddles.value = huddles.value - conversationId
                return@runCatching
            }

            val decoded = clientApi.huddleGet(huddleId).unwrap().huddle
                ?.decodeHuddle(conversationId, huddleId)
            val names = resolveNames(decoded?.participantNames.orEmpty())
            huddles.value = when {
                decoded == null -> huddles.value - conversationId
                else -> huddles.value + (conversationId to decoded.copy(participantNames = names))
            }
        }
    }

    override suspend fun knock(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            clientApi.huddleKnock(channelId = conversationId).requireOk()
            Unit
        }
    }

    override suspend fun cancelKnock(conversationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            clientApi.huddleCancelKnock(channelId = conversationId).requireOk()
            Unit
        }
    }

    private suspend fun resolveNames(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val byId = userDao.findByIds(ids).associateBy { it.id }
        return ids.map { id ->
            byId[id]?.displayName?.takeIf(String::isNotBlank) ?: id
        }
    }
}

private fun JsonObject.requireOk(): JsonObject {
    if (this["ok"]?.jsonPrimitive?.booleanOrNull == false) {
        throw SlackApiException(this["error"]?.jsonPrimitive?.contentOrNull ?: "unknown_error")
    }
    return this
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

private fun JsonObject.decodeIndirectHuddleId(): String? =
    str("huddle_id")
        ?: obj("huddle")?.str("id")
        ?: obj("huddle")?.str("huddle_id")
        ?: obj("huddle")?.str("channel_id")

private fun JsonObject.decodeHuddle(conversationId: String, huddleId: String): HuddleState? {
    if (str("id") == null && str("huddle_id") == null && str("channel_id") == null) return null
    val participants = strList("participants")
        .ifEmpty { strList("member_ids") }
        .ifEmpty { strList("user_ids") }
        .ifEmpty { strList("active_participants") }
    val active = bool("is_active") ?: (str("date_ended") == null && str("date_end") == null)
    return HuddleState(
        conversationId = conversationId,
        huddleId = huddleId,
        active = active,
        participantCount = participants.size,
        participantNames = participants,
    )
}
