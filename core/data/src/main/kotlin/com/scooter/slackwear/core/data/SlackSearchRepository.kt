package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.model.repository.SearchHit
import com.scooter.slackwear.core.model.repository.SearchHitKind
import com.scooter.slackwear.core.model.repository.SearchRepository
import com.scooter.slackwear.core.model.repository.SearchSort
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.model.SearchModulesRequest
import com.scooter.slackwear.core.network.model.SearchModulesResponse
import com.scooter.slackwear.core.network.unwrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SlackSearchRepository(
    private val clientApi: ClientApi,
    private val userDao: UserDao,
) : SearchRepository {

    override suspend fun search(query: String, sort: SearchSort): Result<List<SearchHit>> =
        withContext(Dispatchers.IO) {
            try {
                val response = clientApi.searchModules(
                    SearchModulesRequest(
                        module = SEARCH_MODULE,
                        query = query,
                        count = RESULTS_PER_MODULE,
                        sort = when (sort) {
                            SearchSort.RELEVANCE -> "score"
                            else -> "timestamp"
                        },
                        sortDirection = when (sort) {
                            SearchSort.OLDEST -> "asc"
                            else -> "desc"
                        },
                    ),
                ).unwrap()

                val decoded = decode(response, requested = SEARCH_MODULE)
                val userIds = decoded.asSequence()
                    .filter { it.first == SearchHitKind.MESSAGE }
                    .mapNotNull { it.second.str("user") }
                    .distinct()
                    .toList()
                val names = if (userIds.isEmpty()) emptyMap() else {
                    userDao.findByIds(userIds).associate { it.id to it.displayName }
                }

                Result.success(decoded.mapNotNull { (kind, raw) -> raw.toHit(kind, names) })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private fun decode(
        response: SearchModulesResponse,
        requested: String,
    ): List<Pair<SearchHitKind, JsonObject>> {
        if (response.mixedResults.isNotEmpty()) {
            return response.mixedResults.flatMap { mixed ->
                val kind = mixed.moduleType.toKind() ?: return@flatMap emptyList()
                mixed.results.flatMap { raw -> decodeRows(kind, raw) }
            }
        }
        val kind = response.module.toKind() ?: requested.toKind() ?: return emptyList()
        return decodeRows(kind, response.items)
    }

    private fun decodeRows(kind: SearchHitKind, raw: JsonElement?): List<Pair<SearchHitKind, JsonObject>> {
        val rows = when (raw) {
            is JsonArray -> raw.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(raw)
            else -> emptyList()
        }
        return rows.flatMap { row ->
            if (kind == SearchHitKind.MESSAGE && row.containsKey("messages")) {
                val channel = row.obj("channel") ?: return@flatMap emptyList()
                (row["messages"] as? JsonArray).orEmpty().mapNotNull { message ->
                    (message as? JsonObject)?.let { kind to JsonObject(it + ("channel" to channel)) }
                }
            } else {
                listOf(kind to row)
            }
        }
    }

    private fun JsonObject.toHit(kind: SearchHitKind, names: Map<String, String>): SearchHit? {
        return when (kind) {
            SearchHitKind.MESSAGE -> {
                val ts = str("ts") ?: return null
                val channel = obj("channel") ?: return null
                val channelId = channel.str("id") ?: return null
                SearchHit(
                    kind = SearchHitKind.MESSAGE,
                    id = ts,
                    conversationId = channelId,
                    conversationName = channel.str("name")?.let { "#$it" }.orEmpty(),
                    authorName = str("username")
                        ?: str("user")?.let { names[it]?.takeIf(String::isNotBlank) }
                        ?: str("user").orEmpty(),
                    text = flatten(str("text").orEmpty()),
                )
            }

            SearchHitKind.CHANNEL -> {
                val id = str("id") ?: return null
                val name = str("name")
                SearchHit(
                    kind = SearchHitKind.CHANNEL,
                    id = id,
                    conversationId = id,
                    authorName = "Channel",
                    text = name?.let { "#$it" } ?: id,
                )
            }

            SearchHitKind.PERSON -> {
                val id = str("id") ?: return null
                val profile = obj("profile")
                val display = profile?.str("display_name")
                    ?: profile?.str("real_name")
                    ?: str("real_name")
                    ?: str("username")
                    ?: str("name")
                    ?: id
                SearchHit(
                    kind = SearchHitKind.PERSON,
                    id = id,
                    authorName = "Person",
                    text = display,
                )
            }

            SearchHitKind.FILE -> {
                val id = str("id") ?: return null
                SearchHit(
                    kind = SearchHitKind.FILE,
                    id = id,
                    authorName = "File",
                    text = str("name") ?: str("title") ?: id,
                )
            }
        }
    }
}

private const val SEARCH_MODULE = "messages"

private const val RESULTS_PER_MODULE = 20L

private fun String?.toKind(): SearchHitKind? = when (this) {
    "messages", "message" -> SearchHitKind.MESSAGE
    "channels", "channel" -> SearchHitKind.CHANNEL
    "people", "person", "users", "user" -> SearchHitKind.PERSON
    "files", "file" -> SearchHitKind.FILE
    else -> null
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
