package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.model.UnreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersConversionTest {

    private fun conversation(
        id: String = "C1",
        unread: Int = 0,
        mentions: Int = 0,
        latestTs: String? = "1700000000.000100",
        confidence: UnreadState.Confidence = UnreadState.Confidence.EXACT,
    ) = ConversationEntity(
        id = id,
        name = "general",
        kind = ConversationKind.PUBLIC_CHANNEL,
        topic = "topic",
        isMuted = false,
        isArchived = false,
        counterpartUserId = null,
        isOpen = true,
        latestPreview = "hello",
        latestTs = latestTs,
        lastSeenTs = null,
        unreadCount = unread,
        mentionCount = mentions,
        unreadConfidence = confidence,
        refreshedAtMillis = 0,
    )

    private fun message(
        reactions: String = "[]",
        author: String = "U2",
        threadTs: String? = null,
    ) = MessageEntity(
        conversationId = "C1",
        ts = "1700000000.000100",
        authorId = author,
        text = "hello",
        threadTs = threadTs,
        replyCount = 0,
        reactionsJson = reactions,
        isEdited = false,
        deliveryState = DeliveryState.SENT,
    )

    @Test
    fun aStoredConversationKeepsItsIdentityWhenItBecomesADomainObject() {
        val domain = conversation().toDomain()
        assertEquals("C1", domain.id)
        assertEquals("general", domain.name)
        assertEquals(ConversationKind.PUBLIC_CHANNEL, domain.kind)
        assertEquals("topic", domain.topic)
        assertTrue(domain.isOpen)
        assertFalse(domain.isMuted)
        assertNull(domain.counterpartUserId)
    }

    @Test
    fun unreadStateCarriesTheCountsAndTheConfidenceItWasStoredWith() {
        val state = conversation(unread = 7, mentions = 2).toUnreadState()
        assertEquals("C1", state.conversationId)
        assertEquals(7, state.count)
        assertEquals(2, state.mentionCount)
        assertEquals("1700000000.000100", state.lastActivityTs)
        assertEquals(UnreadState.Confidence.EXACT, state.confidence)

        val guessed = conversation(confidence = UnreadState.Confidence.DERIVED, latestTs = null).toUnreadState()
        assertEquals(UnreadState.Confidence.DERIVED, guessed.confidence)
        assertNull(guessed.lastActivityTs)
    }

    @Test
    fun aReactionIsMineOnlyWhenMyIdIsAmongTheUsers() {
        val raw = """[{"name":"tada","count":2,"users":["U1","U2"]},{"name":"eyes","count":1,"users":["U2"]}]"""
        val mine = message(reactions = raw).toDomain(currentUserId = "U1")
        assertEquals(listOf("tada", "eyes"), mine.reactions.map { it.name })
        assertTrue(mine.reactions.first { it.name == "tada" }.includesMe)
        assertFalse(mine.reactions.first { it.name == "eyes" }.includesMe)

        val theirs = message(reactions = raw).toDomain(currentUserId = "U3")
        assertTrue(theirs.reactions.none { it.includesMe })
    }

    @Test
    fun corruptReactionJsonYieldsNoReactionsRatherThanCrashingTheMessageList() {
        assertTrue(message(reactions = "not json").toDomain("U1").reactions.isEmpty())
        assertTrue(message(reactions = "").toDomain("U1").reactions.isEmpty())
        assertTrue(message(reactions = "{}").toDomain("U1").reactions.isEmpty())
    }

    @Test
    fun threadMetadataIsOverlaidWithoutDisturbingTheMessageItself() {
        val base = message(threadTs = "1699999999.000100").toDomain("U1")
        val enriched = base.withThreadMetadata(null)
        assertEquals(base.ts, enriched.ts)
        assertEquals(base.text, enriched.text)
        assertNull(enriched.isSubscribed)
        assertNull(enriched.threadReplyCount)
    }

    @Test
    fun mentionIdsAreDeduplicatedAndKeepTheOrderTheyWereWrittenIn() {
        assertEquals(
            listOf("U08ALICE", "U08BOB"),
            mentionedUserIds("<@U08ALICE> ping <@U08BOB> and <@U08ALICE> again"),
        )
        assertTrue(mentionedUserIds("no mentions here").isEmpty())
        assertTrue(mentionedUserIds("").isEmpty())
    }

    @Test
    fun aBroadcastIsNotMistakenForAUserMention() {
        assertTrue(mentionedUserIds("<!channel> everyone").isEmpty())
        assertEquals("@channel everyone", flatten("<!channel> everyone"))
        assertEquals("@here", flatten("<!here>"))
    }

    @Test
    fun htmlEntitiesSlackSendsAreDecodedBackIntoPlainText() {
        assertEquals("a & b", flatten("a &amp; b"))
        assertEquals("1 < 2 > 0", flatten("1 &lt; 2 &gt; 0"))
    }

    @Test
    fun codeSpansAndQuotesAreFlattenedForAOneLinePreview() {
        assertEquals("val x = 1", flatten("```val x = 1```"))
        assertEquals("inline", flatten("`inline`"))
        assertEquals("quoted", flatten("> quoted"))
        assertEquals("bold italic struck", flatten("*bold* _italic_ ~struck~"))
    }

    @Test
    fun aPreviewCollapsesWhitespaceWhileABodyKeepsItsParagraphs() {
        assertEquals("one two", flatten("one   \n\n  two"))
        assertEquals("one\n\ntwo", flattenBody("one\n\n\n\ntwo"))
    }

    @Test
    fun snakeCaseInsideAWordIsNotTreatedAsItalics() {
        assertEquals("some_variable_name", flatten("some_variable_name"))
        assertEquals("italic", flatten("_italic_"))
    }
}
