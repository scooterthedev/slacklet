package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.entity.ActivityEntity
import com.scooter.slackwear.core.model.SlackUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MentionAndAuthorNameTest {

    @Test
    fun mentionsResolveToNamesAndTheReaderBecomesYou() {
        val names = MentionNames(
            currentUserId = "U08ME",
            displayNames = mapOf("U08ALICE" to "alice", "U08ME" to "scooter"),
        )
        assertEquals(
            "@alice and @you should see this",
            flattenBody("<@U08ALICE> and <@U08ME> should see this", names),
        )
    }

    @Test
    fun anInlineLabelWinsAndAnUnknownIdIsStillNamedSomeone() {
        val names = MentionNames(displayNames = mapOf("U08ALICE" to "alice"))
        assertEquals("@bob", flattenBody("<@U08BOB|bob>", names))
        assertEquals("@someone", flattenBody("<@U08NOBODY>", names))
    }

    @Test
    fun aPushPreviewReadsAsProseRatherThanRawSlackMarkup() {
        val names = MentionNames(
            currentUserId = "U08ME",
            displayNames = mapOf("U08ALICE" to "alice"),
        )
        assertEquals(
            "@alice shipped it in #general - see the docs, cc @here",
            flatten(
                "<@U08ALICE> *shipped* it in <#C01ABC|general> - see <https://example.com|the docs>, cc <!here>",
                names,
            ),
        )
    }

    @Test
    fun aCustomEmojiSurvivesFlatteningInsteadOfVanishing() {
        assertEquals(":hackclub: ship it", flatten(":hackclub: ship it"))
        assertEquals("shipped :hackclub:", flatten("shipped :hackclub:"))
    }

    @Test
    fun anEmojiOnlyMessageIsNeverFlattenedToNothing() {
        assertEquals(":partyparrot:", flatten(":partyparrot:"))
    }

    @Test
    fun mentionedIdsAreFoundSoTheyCanBeResolvedBeforeFlattening() {
        assertEquals(
            listOf("U08ALICE", "U08BOB"),
            mentionedUserIds("hey <@U08ALICE> and <@U08BOB|bob> and <@U08ALICE> again"),
        )
        assertEquals(emptyList<String>(), mentionedUserIds("no mentions here"))
    }

    @Test
    fun activityAuthorComesFromTheResolvedUserNotTheWord_Slack() {
        val entry = entry("""{"feed_ts":"9","key":"K","item":{"type":"message_reaction","reaction":{"user":"U08ALICE"},"message":{"channel":"C1","ts":"1.000001"}}}""")
        assertEquals("U08ALICE", entry.authorId)

        val resolved = entry.toActivityItem(
            mapOf("U08ALICE" to SlackUser("U08ALICE", "alice", "Alice", "https://example.invalid/a.png")),
        )
        assertEquals("alice", resolved.author.displayName)
        assertEquals("https://example.invalid/a.png", resolved.author.avatarUrl)
    }

    @Test
    fun anEntryWithNoAuthorIsLeftUnnamedRatherThanAttributedToSlack() {
        val entry = entry("""{"feed_ts":"9","key":"K","item":{"type":"thread_v2","bundle_info":{"payload":{"thread_entry":{"channel_id":"C1","thread_ts":"1.000001","latest_ts":"2.000002"}}}}}""")
        assertEquals("", entry.toActivityItem().author.displayName)
    }

    @Test
    fun aDirectMessageAuthorIsRecoveredFromTheConversationCounterpart() {
        val entry = entry("""{"feed_ts":"9","key":"K","item":{"type":"dm","bundle_info":{"payload":{"dm_entry":{"latest_message":{"channel":"D1","ts":"1.000001"}}}}}}""")
        val item = entry.toActivityItem(
            users = mapOf("U08ALICE" to SlackUser("U08ALICE", "alice", "Alice", null)),
            counterparts = mapOf("D1" to "U08ALICE"),
        )
        assertEquals("alice", item.author.displayName)
    }

    private fun entry(raw: String): ActivityEntity =
        Json.parseToJsonElement(raw).let { it as JsonObject }.decodeActivityEntry(emptyMap())!!
}
