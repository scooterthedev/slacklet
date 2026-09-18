package com.scooter.slackwear.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadTest {
    private val expires = System.currentTimeMillis() + 600_000
    private val data = mapOf(
        "v" to "2",
        "kind" to "MENTION",
        "channelId" to "synthetic-channel",
        "ts" to "1700000000.000001",
        "authorId" to "synthetic-author",
        "threadTs" to "1700000000.000000",
        "preview" to "Synthetic local preview",
        "eventId" to "synthetic-event",
        "registrationId" to "00000000-0000-0000-0000-000000000000",
        "teamId" to "synthetic-team",
        "slackUserId" to "synthetic-user",
        "expiresAt" to expires.toString(),
    )

    @Test
    fun preservesExistingPayloadForEveryKind() {
        PushKind.entries.forEach { kind ->
            assertEquals(
                PushPayload(kind, data.getValue("channelId"), data.getValue("ts"), data.getValue("authorId"),
                    data["threadTs"], data.getValue("preview"), data.getValue("eventId"),
                    data.getValue("registrationId"), data.getValue("teamId"), data.getValue("slackUserId"), expires),
                parsePushPayload(data + ("kind" to kind.name)),
            )
        }
    }

    @Test
    fun rejectsMissingOrInvalidRequiredFields() {
        listOf("v", "kind", "channelId", "ts", "authorId", "teamId", "slackUserId", "eventId", "registrationId", "expiresAt", "preview").forEach { field ->
            assertNull(parsePushPayload(data - field))
        }
        assertNull(parsePushPayload(data + ("v" to "1")))
        assertNull(parsePushPayload(data + ("kind" to "UNKNOWN")))
        assertNull(parsePushPayload(data + ("ts" to "1700000000")))
        assertNull(parsePushPayload(data + ("threadTs" to "garbage")))
        assertNull(parsePushPayload(data + ("registrationId" to "not-a-uuid")))
        assertNull(parsePushPayload(data + ("channelId" to "bad id!")))
        assertNull(parsePushPayload(data + ("expiresAt" to (System.currentTimeMillis() - 600_000).toString())))
    }

    @Test
    fun aWatchWhoseClockLagsTheRelayStillGetsItsNotifications() {
        val now = System.currentTimeMillis()
        val relayStamped = now + MAX_PUSH_LIFETIME_MILLIS
        listOf(0L, 3_589L, 60_000L, CLOCK_SKEW_ALLOWANCE_MILLIS).forEach { skew ->
            assertTrue("rejected a live push with ${skew}ms of clock skew", freshEnough(relayStamped, now - skew))
        }
        assertFalse(freshEnough(relayStamped, now - CLOCK_SKEW_ALLOWANCE_MILLIS - 60_000))
        assertTrue(freshEnough(now - 60_000, now))
        assertFalse(freshEnough(now - CLOCK_SKEW_ALLOWANCE_MILLIS - 60_000, now))
    }

    @Test
    fun threadTsRemainsOptional() {
        assertEquals(
            PushPayload(PushKind.MENTION, data.getValue("channelId"), data.getValue("ts"), data.getValue("authorId"),
                null, data.getValue("preview"), data.getValue("eventId"), data.getValue("registrationId"),
                data.getValue("teamId"), data.getValue("slackUserId"), expires),
            parsePushPayload(data - "threadTs"),
        )
    }

    @Test
    fun notificationIdentityIsStableAcrossMilliseconds() {
        val first = parsePushPayload(data)!!.notificationId()
        val second = parsePushPayload(data + ("ts" to "1700000000.000999"))!!.notificationId()
        assertEquals(first, second)
        val thread = parsePushPayload(data + ("threadTs" to "1700000000.000002"))!!.notificationId()
        assertFalse(first == thread)
    }

    @Test
    fun duplicateEventIdIsRecognisedByPolicy() {
        val payload = parsePushPayload(data)!!
        val seen = mutableSetOf<String>()
        assertTrue(seen.add(payload.identity() + payload.eventId))
        assertFalse(seen.add(payload.identity() + payload.eventId))
    }

    @Test
    fun rejectedCredentialsNeverReachNotificationPosting() {
        val binding = RelayBinding("synthetic-team", "synthetic-user", "synthetic-device", "synthetic-capability", "00000000-0000-0000-0000-000000000000")
        assertTrue(binding.matches(NotificationAccount("synthetic-team", "synthetic-user")))
        assertFalse(binding.matches(NotificationAccount("synthetic-team", "other-user")))
        assertFalse(binding.matches(null))
    }
}
