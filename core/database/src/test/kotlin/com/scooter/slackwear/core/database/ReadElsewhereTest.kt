package com.scooter.slackwear.core.database

import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.mergeRoster
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.UnreadState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadElsewhereTest {

    @Test
    fun readingOnAnotherDeviceClearsTheUnreadHere() {
        val stored = conversation(latest = "200.000000", lastRead = "100.000000", unread = 4)
        val fromSlack = stored.copy(lastSeenTs = "200.000000", unreadCount = 0)

        val merged = stored.mergeRoster(fromSlack, explicitOpen = null)

        assertEquals(0, merged.unreadCount)
        assertEquals(0, merged.mentionCount)
        assertEquals(UnreadState.Confidence.EXACT, merged.unreadConfidence)
    }

    @Test
    fun readMarkerPastTheLastMessageAlsoCounts() {
        val stored = conversation(latest = "200.000000", lastRead = null, unread = 2)
        val fromSlack = stored.copy(lastSeenTs = "300.000000")

        assertEquals(0, stored.mergeRoster(fromSlack, explicitOpen = null).unreadCount)
    }

    @Test
    fun anUnreadMessageArrivingAfterTheReadMarkerStaysUnread() {
        val stored = conversation(latest = "100.000000", lastRead = "100.000000", unread = 0)
        val fromSlack = stored.copy(latestTs = "300.000000")

        val merged = stored.mergeRoster(fromSlack, explicitOpen = null)

        assertEquals("300.000000", merged.latestTs)
        assertEquals(UnreadState.Confidence.DERIVED, merged.unreadConfidence)
    }

    @Test
    fun aStaleReadMarkerNeverMovesBackwards() {
        val stored = conversation(latest = "300.000000", lastRead = "300.000000", unread = 0)
        val fromSlack = stored.copy(lastSeenTs = "100.000000")

        val merged = stored.mergeRoster(fromSlack, explicitOpen = null)

        assertEquals("300.000000", merged.lastSeenTs)
        assertEquals(0, merged.unreadCount)
    }

    @Test
    fun anExactDirectMessageCountFromSlackStillWins() {
        val stored = conversation(
            latest = "200.000000",
            lastRead = "100.000000",
            unread = 1,
            kind = ConversationKind.DIRECT_MESSAGE,
        )
        val fromSlack = stored.copy(unreadCount = 5, unreadConfidence = UnreadState.Confidence.EXACT)

        assertEquals(5, stored.mergeRoster(fromSlack, explicitOpen = null).unreadCount)
    }

    private fun conversation(
        latest: String?,
        lastRead: String?,
        unread: Int,
        kind: ConversationKind = ConversationKind.PUBLIC_CHANNEL,
    ) = ConversationEntity(
        id = "C1",
        name = "general",
        kind = kind,
        topic = null,
        isMuted = false,
        isArchived = false,
        counterpartUserId = null,
        isOpen = true,
        latestPreview = "hello",
        latestTs = latest,
        lastSeenTs = lastRead,
        unreadCount = unread,
        mentionCount = if (unread > 0) 1 else 0,
        unreadConfidence = UnreadState.Confidence.DERIVED,
        refreshedAtMillis = 0,
    )
}
