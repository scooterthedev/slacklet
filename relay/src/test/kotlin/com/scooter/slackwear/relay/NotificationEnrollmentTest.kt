package com.scooter.slackwear.relay

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class NotificationEnrollmentTest {
    @get:Rule val directory = TemporaryFolder()

    private val sender = object : PushSender {
        override val isReady = false
        override fun send(device: Device, payload: Map<String, String>) = PushOutcome.Retryable
    }
    private val dnd = object : DndChecker {
        override fun isSnoozed(device: Device) = false
    }
    private val json = Json { encodeDefaults = true }
    private val slackToken = "xoxc-synthetic-session"
    private val device = "00000000-0000-0000-0000-0000000000aa"
    private val capability = "a".repeat(64)

    private fun verifier(identity: VerifiedIdentity?) = IdentityVerifier { token ->
        identity.takeIf { token == slackToken }
    }

    private fun body(
        teamId: String = "T1",
        deviceId: String = device,
        capabilityHash: String = capability,
        fcmToken: String = "synthetic-fcm",
        token: String = slackToken,
    ) = json.encodeToString(EnrollmentRequest(teamId, deviceId, capabilityHash, fcmToken, token))

    private fun state(): String = Files.readString(Path.of(directory.root.absolutePath, "notification-enrollment-v2.json"))

    @Test
    fun `a signed in watch enrolls itself with no code and no operator step`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val enrollment = Json { ignoreUnknownKeys = true }.decodeFromString<EnrollmentResponse>(response.bodyAsText())
        assertEquals("T1", enrollment.teamId)
        assertEquals("U1", enrollment.userId)
        assertTrue(validUuid(enrollment.registrationId))
    }

    @Test
    fun `the device is bound to the user Slack verified, never to a claim in the request`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U-verified", setOf("T1"))),
            )
        }
        val claimed = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"teamId":"T1","deviceId":"$device","capabilityHash":"$capability","fcmToken":"synthetic-fcm","slackToken":"$slackToken","slackUserId":"U-victim"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, claimed.status)
        assertTrue(DeviceStore(directory.root.absolutePath).all().isEmpty())

        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("U-verified", DeviceStore(directory.root.absolutePath).all().single().slackUserId)
        assertFalse(state().contains("U-victim"))
    }

    @Test
    fun `a token Slack does not recognise enrolls nothing`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(null),
            )
        }
        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body(token = "xoxc-not-a-session"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(DeviceStore(directory.root.absolutePath).all().isEmpty())
    }

    @Test
    fun `a team the token does not belong to is refused`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body(teamId = "T-OTHER"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(DeviceStore(directory.root.absolutePath).all().isEmpty())
    }

    @Test
    fun `an enterprise grid id is accepted because events carry it`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1", "E1"))),
            )
        }
        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body(teamId = "E1"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("E1", DeviceStore(directory.root.absolutePath).all().single().teamId)
    }

    @Test
    fun `re-enrolling the same watch keeps one device and rotates its capability`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/devices/enroll") { contentType(ContentType.Application.Json); setBody(body()) }.status,
        )
        val first = DeviceStore(directory.root.absolutePath).all().single()
        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body(capabilityHash = "b".repeat(64), fcmToken = "rotated-fcm"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val devices = DeviceStore(directory.root.absolutePath).all()
        assertEquals(1, devices.size)
        assertEquals(first.registrationId, devices.single().registrationId)
        assertEquals("b".repeat(64), devices.single().capabilityHash)
        assertEquals("rotated-fcm", devices.single().fcmToken)
        assertNotEquals(first.tokenGeneration, devices.single().tokenGeneration)
    }

    @Test
    fun `the Slack session token is never written to disk`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        client.post("/v2/devices/enroll") { contentType(ContentType.Application.Json); setBody(body()) }
        assertFalse(state().contains(slackToken))
        assertFalse(state().contains("xoxc"))
    }

    @Test
    fun `a device revoked for a dead push token comes back on the next launch`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        client.post("/v2/devices/enroll") { contentType(ContentType.Application.Json); setBody(body()) }
        val store = DeviceStore(directory.root.absolutePath)
        assertTrue(store.removeIfCurrent(store.all().single()))
        assertTrue(store.all().isEmpty())
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/devices/enroll") {
                contentType(ContentType.Application.Json)
                setBody(body(fcmToken = "fresh-fcm"))
            }.status,
        )
        assertEquals("fresh-fcm", store.all().single().fcmToken)
    }

    @Test
    fun `only a successful auth test yields an identity`() {
        val lenient = Json { ignoreUnknownKeys = true }
        assertEquals(
            VerifiedIdentity("U1", setOf("T1")),
            parseAuthTest(lenient, """{"ok":true,"user_id":"U1","team_id":"T1"}"""),
        )
        assertEquals(
            VerifiedIdentity("U1", setOf("T1", "E1")),
            parseAuthTest(lenient, """{"ok":true,"user_id":"U1","team_id":"T1","enterprise_id":"E1"}"""),
        )
        assertNull(parseAuthTest(lenient, """{"ok":false,"error":"invalid_auth"}"""))
        assertNull(parseAuthTest(lenient, """{"ok":true,"team_id":"T1"}"""))
        assertNull(parseAuthTest(lenient, """{"ok":true,"user_id":"U1"}"""))
        assertNull(parseAuthTest(lenient, "not json"))
        assertFalse(validSlackToken("not-a-slack-token"))
        assertFalse(validSlackToken("xoxc with space"))
        assertTrue(validSlackToken("xoxc-synthetic-session"))
    }

    @Test
    fun `a grid watch enrolled on the enterprise id still receives workspace events`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1", "E1"))),
            )
        }
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v2/devices/enroll") { contentType(ContentType.Application.Json); setBody(body(teamId = "E1")) }.status,
        )
        val store = DeviceStore(directory.root.absolutePath)
        assertEquals("E1", store.all().single().teamId)

        val envelope = SlackEventEnvelope(
            type = "event_callback", teamId = "T1", eventId = "Ev1",
            authorizations = listOf(Authorization("U1", "T1", false)),
            event = SlackEvent(type = "message", user = "U2", text = "hello", ts = "123.456", channel = "D1", channelType = "im"),
        )
        val planned = EventRouter(store).plan(envelope).single()
        assertEquals("U1", planned.key.userId)
        assertEquals("E1", planned.payload["teamId"])
        assertEquals("E1", planned.key.teamId)
    }

    @Test
    fun `a device enrolled before team verification is asked to enroll again`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        val store = DeviceStore(directory.root.absolutePath)
        store.register(Device("U1", "T1", "legacy-fcm", capabilityHash = notificationDigest("legacy-capability")))
        val legacy = store.all().single()
        assertTrue(legacy.teamIds.isEmpty())

        val refused = client.put("/v2/devices/${'$'}{legacy.registrationId}") {
            header("Authorization", "Bearer legacy-capability")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DeviceUpdateRequest("new-fcm", NotificationSettings())))
        }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)

        val reEnrolled = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(body())
        }
        assertEquals(HttpStatusCode.OK, reEnrolled.status)
        assertEquals(setOf("T1"), DeviceStore(directory.root.absolutePath).all().single { it.deviceId == device }.teamIds)
    }

    @Test
    fun `a malformed body enrolls nothing`() = testApplication {
        application {
            relayModule(
                Config(0, "synthetic-signing-secret", null, directory.root.absolutePath),
                sender, InMemoryDeliveryStore(), dnd, verifier(VerifiedIdentity("U1", setOf("T1"))),
            )
        }
        val response = client.post("/v2/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody("{\"teamId\":\"T1\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(DeviceStore(directory.root.absolutePath).all().isEmpty())
    }
}
