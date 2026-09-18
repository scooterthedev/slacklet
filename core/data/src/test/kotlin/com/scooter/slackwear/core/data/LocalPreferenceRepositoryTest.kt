package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.CustomEmojiDao
import com.scooter.slackwear.core.database.dao.UsageDao
import com.scooter.slackwear.core.database.entity.CustomEmojiEntity
import com.scooter.slackwear.core.database.entity.UsageEntity
import com.scooter.slackwear.core.database.entity.UsageKind
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.EmojiListResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LocalPreferenceRepositoryTest {

    private val stored = mutableListOf<CustomEmojiEntity>()
    private val written = mutableListOf<UsageEntity>()
    private var existingCount = 0
    private var usage = emptyList<UsageEntity>()
    private var remoteEmoji: Map<String, String> = emptyMap()
    private var emojiCalls = 0

    private fun emojiDao() = stub<CustomEmojiDao> { name, args ->
        when (name) {
            "count" -> existingCount
            "upsert" -> {
                @Suppress("UNCHECKED_CAST")
                stored += args[0] as List<CustomEmojiEntity>
                Unit
            }
            "namesMatching" -> stored.map { it.name }.filter {
                (args[0] as String).trim('%') in it
            }.sortedBy { it.length }
            "observeAll" -> flowOf(stored.toList())
            else -> error(name)
        }
    }

    private fun usageDao() = stub<UsageDao> { name, args ->
        when (name) {
            "observe" -> flowOf(usage)
            "find" -> usage.firstOrNull { it.id == args[0] }
            "upsert" -> { written += args[0] as UsageEntity; Unit }
            else -> error(name)
        }
    }

    private fun api() = stub<SlackApi> { name, _ ->
        when (name) {
            "emojiList" -> { emojiCalls++; EmojiListResponse(ok = true, emoji = remoteEmoji) }
            else -> error(name)
        }
    }

    private fun repository(now: () -> Long = { 0L }) =
        LocalPreferenceRepository(usageDao(), emojiDao(), api(), now)

    @Test
    fun customEmojiWithRealImagesAreStored() = runTest {
        remoteEmoji = mapOf("blobhaj" to "https://emoji.slack/blobhaj.png", "loll" to "https://emoji.slack/loll.gif")
        assertTrue(repository().syncCustomEmoji().isSuccess)
        assertEquals(setOf("blobhaj", "loll"), stored.map { it.name }.toSet())
    }

    @Test
    fun anAliasIsResolvedToTheImageItPointsAt() = runTest {
        remoteEmoji = mapOf(
            "blobhaj" to "https://emoji.slack/blobhaj.png",
            "hajblob" to "alias:blobhaj",
        )
        assertTrue(repository().syncCustomEmoji().isSuccess)
        assertEquals(
            "https://emoji.slack/blobhaj.png",
            stored.single { it.name == "hajblob" }.imageUrl,
        )
    }

    @Test
    fun anAliasPointingAtAStandardEmojiIsDroppedRatherThanStored() = runTest {
        remoteEmoji = mapOf("sobbing" to "alias:sob")
        assertTrue(repository().syncCustomEmoji().isSuccess)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aSyncIsSkippedEntirelyWhenAnythingIsAlreadyStored() = runTest {
        existingCount = 1
        remoteEmoji = mapOf("blobhaj" to "https://emoji.slack/blobhaj.png")
        assertTrue(repository().syncCustomEmoji().isSuccess)
        assertEquals(0, emojiCalls)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun anEmptySearchQueryReturnsNothingWithoutTouchingTheDatabase() = runTest {
        assertTrue(repository().searchEmoji("").isEmpty())
        assertTrue(repository().searchEmoji("   ").isEmpty())
    }

    @Test
    fun searchPrefersNamesThatStartWithTheQueryThenShorterOnes() = runTest {
        stored += listOf(
            CustomEmojiEntity("unblob", "https://e/1.png"),
            CustomEmojiEntity("blob", "https://e/2.png"),
            CustomEmojiEntity("blobhaj", "https://e/3.png"),
        )
        assertEquals(listOf("blob", "blobhaj", "unblob"), repository().searchEmoji("blob"))
    }

    @Test
    fun recordingAReactionIncrementsItsCountFromWhateverWasThereBefore() = runTest {
        usage = listOf(UsageEntity("REACTION:tada", UsageKind.REACTION, "tada", 4, 0))
        repository(now = { 1_234L }).recordReaction("tada")
        val saved = written.single()
        assertEquals("REACTION:tada", saved.id)
        assertEquals(5, saved.useCount)
        assertEquals(1_234L, saved.lastUsedMillis)
    }

    @Test
    fun aFirstTimeReactionStartsAtOne() = runTest {
        repository().recordReaction("parrot")
        assertEquals(1, written.single().useCount)
        assertEquals(UsageKind.REACTION, written.single().kind)
    }

    @Test
    fun quickRepliesAreRecordedUnderTheirOwnKind() = runTest {
        repository().recordQuickReply("On it")
        assertEquals(UsageKind.QUICK_REPLY, written.single().kind)
        assertEquals("QUICK_REPLY:On it", written.single().id)
    }

    @Test
    fun recentlyUsedReactionsOutrankOlderOnesAndDefaultsFillTheRest() = runTest {
        val day = 86_400_000L
        usage = listOf(
            UsageEntity("REACTION:old", UsageKind.REACTION, "old", 10, 0),
            UsageEntity("REACTION:fresh", UsageKind.REACTION, "fresh", 3, 60 * day),
        )
        val row = repository(now = { 60 * day }).observeFrequentReactions().first()
        assertEquals("fresh", row.first())
        assertTrue(row.contains("old"))
        assertTrue("defaults should backfill the row", row.size > 2)
        assertFalse("no duplicates", row.size != row.distinct().size)
    }

    @Test
    fun quickReplyDefaultsAreOfferedWhenNothingHasBeenLearned() = runTest {
        val row = repository().observeQuickReplies().first()
        assertTrue(row.isNotEmpty())
        assertEquals(row.distinct(), row)
    }

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
