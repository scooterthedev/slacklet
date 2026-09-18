package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.CustomEmojiDao
import com.scooter.slackwear.core.database.dao.UsageDao
import com.scooter.slackwear.core.database.entity.CustomEmojiEntity
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.unwrap
import com.scooter.slackwear.core.database.entity.UsageEntity
import com.scooter.slackwear.core.database.entity.UsageKind
import com.scooter.slackwear.core.model.repository.PreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max

class LocalPreferenceRepository(
    private val usageDao: UsageDao,
    private val customEmojiDao: CustomEmojiDao,
    private val api: SlackApi,
    private val now: () -> Long = System::currentTimeMillis,
) : PreferenceRepository {

    override fun observeFrequentReactions(): Flow<List<String>> =
        usageDao.observe(UsageKind.REACTION).map { stats ->
            blendWithDefaults(stats.rankedByFrecency(now()), DEFAULT_REACTIONS)
        }

    override fun observeQuickReplies(): Flow<List<String>> =
        usageDao.observe(UsageKind.QUICK_REPLY).map { stats ->
            blendWithDefaults(stats.rankedByFrecency(now()), DEFAULT_QUICK_REPLIES)
        }

    override suspend fun recordReaction(emoji: String) = record(UsageKind.REACTION, emoji)

    override suspend fun recordQuickReply(text: String) = record(UsageKind.QUICK_REPLY, text)

    override fun observeCustomEmoji(): Flow<Map<String, String>> =
        customEmojiDao.observeAll().map { emoji -> emoji.associate { it.name to it.imageUrl } }

    override suspend fun syncCustomEmoji(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (customEmojiDao.count() > 0) return@runCatching

            val emoji = api.emojiList().unwrap().emoji
            customEmojiDao.upsert(
                emoji.mapNotNull { (name, url) ->

                    val resolved = if (url.startsWith(ALIAS_PREFIX)) {
                        emoji[url.removePrefix(ALIAS_PREFIX)]
                    } else {
                        url
                    }
                    resolved?.takeIf { it.startsWith("http") }?.let { CustomEmojiEntity(name, it) }
                },
            )
        }
    }

    override suspend fun searchEmoji(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val custom = customEmojiDao.namesMatching("%$trimmed%")
        val standard = Emoji.searchable().filter { trimmed in it }

        val byRelevance = compareByDescending<String> { it.startsWith(trimmed) }.thenBy { it.length }

        (custom.sortedWith(byRelevance) + standard.sortedWith(byRelevance))
            .distinct()
            .take(MAX_SEARCH_RESULTS)
    }

    private suspend fun record(kind: UsageKind, value: String) = withContext(Dispatchers.IO) {
        val id = "${kind.name}:$value"
        val existing = usageDao.find(id)
        usageDao.upsert(
            UsageEntity(
                id = id,
                kind = kind,
                value = value,
                useCount = (existing?.useCount ?: 0) + 1,
                lastUsedMillis = now(),
            ),
        )
    }

    private fun blendWithDefaults(learned: List<String>, defaults: List<String>): List<String> =
        (learned + defaults.filterNot(learned::contains)).take(ROW_SIZE)

    private companion object {
        const val ALIAS_PREFIX = "alias:"
        const val ROW_SIZE = 12
        const val MAX_SEARCH_RESULTS = 32

        val DEFAULT_REACTIONS = listOf(
            "+1", "eyes", "tada", "white_check_mark", "heart", "joy",
            "rocket", "fire", "100", "pray", "thinking_face", "clap",
        )

        val DEFAULT_QUICK_REPLIES = listOf(
            "On it", "Thanks!", "Sounds good", "Give me 10", "Done", "Looking now",
        )
    }
}

private fun List<UsageEntity>.rankedByFrecency(now: Long): List<String> =
    sortedByDescending { entity ->
        val ageDays = max(0L, now - entity.lastUsedMillis) / MILLIS_PER_DAY.toDouble()
        entity.useCount * Math.exp(-DECAY_PER_DAY * ageDays)
    }.map(UsageEntity::value)

private const val MILLIS_PER_DAY = 86_400_000L
private val DECAY_PER_DAY = ln(2.0) / 14.0
