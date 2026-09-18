package com.scooter.slackwear.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceStoreTest {
    @get:Rule val directory = TemporaryFolder()

    private val capability = "a".repeat(64)
    private val deviceId = "00000000-0000-0000-0000-0000000000aa"
    private val identity = VerifiedIdentity("U1", setOf("T1", "E1"))

    private fun store() = DeviceStore(directory.root.absolutePath)

    private fun request(
        teamId: String = "T1",
        capabilityHash: String = notificationDigest(capability),
        fcmToken: String = "synthetic-fcm",
        device: String = deviceId,
    ) = EnrollmentRequest(teamId, device, capabilityHash, fcmToken, "xoxc-synthetic-session")

    private fun enrolled(): Pair<DeviceStore, Device> {
        val s = store()
        assertNotNull(s.enroll(request(), identity))
        return s to s.all().single()
    }

    @Test
    fun `a device is found only by its own capability`() {
        val (s, device) = enrolled()
        assertNotNull(s.authenticated(device.registrationId, capability))
        assertNull(s.authenticated(device.registrationId, "b".repeat(64)))
    }

    @Test
    fun `a malformed capability is rejected before any lookup happens`() {
        val (s, device) = enrolled()
        assertNull(s.authenticated(device.registrationId, "not-a-capability"))
        assertNull(s.authenticated(device.registrationId, ""))
        assertNull(s.authenticated("not-a-uuid", capability))
        assertFalse(s.update(device.registrationId, "short", DeviceUpdateRequest("t", NotificationSettings())))
        assertFalse(s.revoke(device.registrationId, "short"))
    }

    @Test
    fun `an update needs the right capability and refreshes the token generation`() {
        val (s, device) = enrolled()
        assertFalse(s.update(device.registrationId, "b".repeat(64), DeviceUpdateRequest("new", NotificationSettings())))
        assertEquals(device.fcmToken, s.all().single().fcmToken)

        assertTrue(s.update(device.registrationId, capability, DeviceUpdateRequest("new", NotificationSettings(allActivity = true))))
        val updated = s.all().single()
        assertEquals("new", updated.fcmToken)
        assertTrue(updated.settings.allActivity)
        assertFalse(device.tokenGeneration == updated.tokenGeneration)
    }

    @Test
    fun `an update carrying a Slack token shaped value is refused`() {
        val (s, device) = enrolled()
        assertFalse(s.update(device.registrationId, capability, DeviceUpdateRequest("xoxc-leaked", NotificationSettings())))
        assertFalse(s.update(device.registrationId, capability, DeviceUpdateRequest("has space", NotificationSettings())))
        assertFalse(s.update(device.registrationId, capability, DeviceUpdateRequest("", NotificationSettings())))
    }

    @Test
    fun `a revoked device stops being visible and cannot be revoked twice into a live state`() {
        val (s, device) = enrolled()
        assertTrue(s.revoke(device.registrationId, capability))
        assertTrue(s.all().isEmpty())
        assertNull(s.find("U1"))
        assertTrue(s.forAccount("T1", "U1").isEmpty())
    }

    @Test
    fun `an enrollment is refused when the request is malformed`() {
        val s = store()
        assertNull(s.enroll(request(capabilityHash = "short"), identity))
        assertNull(s.enroll(request(device = "not-a-uuid"), identity))
        assertNull(s.enroll(request(fcmToken = ""), identity))
        assertNull(s.enroll(request(fcmToken = "xoxc-looks-like-a-slack-token"), identity))
        assertNull(s.enroll(request(teamId = "lowercase"), identity))
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `a device answers to every team Slack verified for it`() {
        val (s, _) = enrolled()
        assertEquals(1, s.forAnyTeam(setOf("T1"), "U1").size)
        assertEquals(1, s.forAnyTeam(setOf("E1"), "U1").size)
        assertEquals(1, s.forAnyTeam(setOf("T-OTHER", "E1"), "U1").size)
        assertTrue(s.forAnyTeam(setOf("T-OTHER"), "U1").isEmpty())
        assertTrue(s.forAnyTeam(setOf("T1"), "U-OTHER").isEmpty())
    }

    @Test
    fun `a generation lookup only matches the exact push token generation`() {
        val (s, device) = enrolled()
        assertNotNull(s.findByGeneration("T1", "U1", device.tokenGeneration))
        assertNull(s.findByGeneration("T1", "U1", "stale-generation"))
        assertNull(s.findByGeneration("T-OTHER", "U1", device.tokenGeneration))
    }

    @Test
    fun `a device whose push token already moved on is not removed by a stale failure`() {
        val (s, device) = enrolled()
        assertTrue(s.update(device.registrationId, capability, DeviceUpdateRequest("rotated", NotificationSettings())))
        assertFalse(s.removeIfCurrent(device))
        assertEquals(1, s.all().size)
        assertTrue(s.removeIfCurrent(s.all().single()))
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `settings can be updated for a signed in user and nobody else`() {
        val (s, _) = enrolled()
        assertTrue(s.updateSettings("U1", NotificationSettings(mentions = false)))
        assertFalse(s.all().single().settings.mentions)
        assertFalse(s.updateSettings("U-OTHER", NotificationSettings()))
    }

    @Test
    fun `state written by one store instance is read back by the next`() {
        val (_, device) = enrolled()
        val reopened = store()
        assertEquals(device.registrationId, reopened.all().single().registrationId)
        assertNotNull(reopened.authenticated(device.registrationId, capability))
    }
}
