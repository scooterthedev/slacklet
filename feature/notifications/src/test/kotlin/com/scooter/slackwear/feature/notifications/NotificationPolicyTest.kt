package com.scooter.slackwear.feature.notifications

import com.scooter.slackwear.core.model.repository.NotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {

    private class MemoryLog : DeliveryLog {
        val stored = LinkedHashMap<String, Long>()
        override fun entries(): Map<String, Long> = stored
        override fun record(key: String, expiresAt: Long) { stored[key] = expiresAt }
        override fun forget(keys: Collection<String>) { keys.forEach(stored::remove) }
        override fun clear() = stored.clear()
    }

    private val account = NotificationAccount("synthetic-team", "synthetic-user")
    private val binding = RelayBinding(
        "synthetic-team", "synthetic-user", "synthetic-device", "synthetic-capability",
        "00000000-0000-0000-0000-000000000001",
    )
    private var moment = 1_000_000L
    private val log = MemoryLog()

    private fun policy(
        settings: NotificationSettings = NotificationSettings(),
        current: RelayBinding? = binding,
        who: NotificationAccount? = account,
    ) = NotificationPolicy(log, { current }, { who }, { moment }).also { it.settings(settings) }

    private fun payload(
        kind: PushKind = PushKind.MENTION,
        eventId: String = "synthetic-event",
        registrationId: String = binding.registrationId!!,
    ) = PushPayload(
        kind, "synthetic-channel", "1700000000.000001", "synthetic-author", null,
        "Synthetic preview", eventId, registrationId, "synthetic-team", "synthetic-user",
        moment + 600_000,
    )

    @Test
    fun followingSlackDndDoesNotSuppressDelivery() {
        assertTrue(policy(NotificationSettings(followSlackDnd = true)).accept(payload()))
    }

    @Test
    fun theSameEventIsOnlyDeliveredOnce() {
        val policy = policy()
        assertTrue(policy.accept(payload()))
        assertFalse(policy.accept(payload()))
        assertTrue(policy.accept(payload(eventId = "synthetic-other")))
    }

    @Test
    fun dedupSurvivesProcessRestart() {
        assertTrue(policy().accept(payload()))
        assertFalse(policy().accept(payload()))
    }

    @Test
    fun aPayloadForAnotherRegistrationIsRejected() {
        assertFalse(policy().accept(payload(registrationId = "00000000-0000-0000-0000-000000000002")))
        assertFalse(policy(current = null).accept(payload()))
        assertFalse(policy(who = NotificationAccount("synthetic-team", "other-user")).accept(payload()))
    }

    @Test
    fun everyKindHonoursItsOwnSetting() {
        assertFalse(policy(NotificationSettings(mentions = false)).accept(payload(PushKind.MENTION)))
        assertFalse(policy(NotificationSettings(directMessages = false)).accept(payload(PushKind.DIRECT_MESSAGE)))
        assertFalse(policy(NotificationSettings(threadReplies = false)).accept(payload(PushKind.THREAD_REPLY)))
        assertFalse(policy(NotificationSettings(allActivity = false)).accept(payload(PushKind.CHANNEL_ACTIVITY)))
        assertTrue(policy(NotificationSettings(allActivity = true)).accept(payload(PushKind.CHANNEL_ACTIVITY)))
    }

    @Test
    fun disablingPushToWatchStopsEverything() {
        assertFalse(policy(NotificationSettings(pushToWatch = false)).accept(payload()))
    }

    @Test
    fun expiredPayloadsAreRejected() {
        val policy = policy()
        val stale = payload().copy(expiresAt = moment - CLOCK_SKEW_ALLOWANCE_MILLIS - 60_000)
        assertFalse(policy.accept(stale))
        val faraway = payload().copy(expiresAt = moment + MAX_PUSH_LIFETIME_MILLIS + CLOCK_SKEW_ALLOWANCE_MILLIS + 60_000)
        assertFalse(policy.accept(faraway))
        val relayStampedWhileWatchLags = payload().copy(expiresAt = moment + 3_589 + MAX_PUSH_LIFETIME_MILLIS)
        assertTrue(policy.accept(relayStampedWhileWatchLags))
    }

    @Test
    fun expiredEntriesAreReclaimedSoDeliveryNeverJams() {
        val policy = policy()
        repeat(1024) { index ->
            assertTrue(policy.accept(payload(eventId = "synthetic-event-$index")))
        }
        moment += 700_000
        assertTrue(policy.accept(payload(eventId = "synthetic-after-expiry")))
        assertEquals(1, log.entries().size)
    }
}
