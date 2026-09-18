package com.scooter.slackwear.relay

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class EventCallbackTest {
    @get:Rule val directory = TemporaryFolder()
    private val secret = "synthetic-signing-secret"
    private val eventJson = """{"type":"event_callback","team_id":"T1","event_id":"Ev1","authorizations":[{"user_id":"U1","team_id":"T1","is_bot":false}],"event":{"type":"message","user":"U2","text":"hello","ts":"123.456","channel":"D1","channel_type":"im"}}"""
    private val sender = object : PushSender {
        override val isReady = false
        override fun send(device: Device, payload: Map<String, String>) = PushOutcome.Retryable
    }
    private val dnd = object : DndChecker {
        override fun isSnoozed(device: Device) = false
    }

    @Test
    fun `signed callback enqueues before ack and duplicate does not add delivery`() = testApplication {
        val store = InMemoryDeliveryStore()
        DeviceStore(directory.root.absolutePath).register(Device("U1", "T1", "synthetic-token"))
        application { relayModule(Config(0, secret, null, directory.root.absolutePath), sender, store, dnd) }
        repeat(2) {
            val response = client.post("/slack/events") {
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                header("X-Slack-Request-Timestamp", timestamp)
                header("X-Slack-Signature", sign(timestamp, eventJson))
                setBody(eventJson)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, store.snapshot().size)
            assertEquals(DeliveryState.Pending, store.snapshot().single().state)
        }
    }

    @Test
    fun `full store returns service unavailable and unsigned request never enqueues`() = testApplication {
        var enqueueCalls = 0
        val store = object : DeliveryStore {
            override fun enqueue(deliveries: List<Delivery>, now: Long): Boolean {
                enqueueCalls++
                return false
            }
            override fun due(now: Long): DeliveryEntry? = null
            override fun update(entry: DeliveryEntry) = Unit
            override fun nextAttemptAt(now: Long): Long? = null
        }
        application { relayModule(Config(0, secret, null, directory.root.absolutePath), sender, store, dnd) }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/slack/events") { setBody(eventJson) }.status)
        assertEquals(0, enqueueCalls)
        val response = client.post("/slack/events") {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            header("X-Slack-Request-Timestamp", timestamp)
            header("X-Slack-Signature", sign(timestamp, eventJson))
            setBody(eventJson)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(1, enqueueCalls)
    }

    @Test
    fun `missing event id is rejected before queue admission`() = testApplication {
        val store = InMemoryDeliveryStore()
        application { relayModule(Config(0, secret, null, directory.root.absolutePath), sender, store, dnd) }
        val missingId = eventJson.replace("\"event_id\":\"Ev1\",", "")
        val response = client.post("/slack/events") {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            header("X-Slack-Request-Timestamp", timestamp)
            header("X-Slack-Signature", sign(timestamp, missingId))
            setBody(missingId)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(store.snapshot().isEmpty())
    }

    private fun sign(timestamp: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "v0=" + mac.doFinal("v0:$timestamp:$value".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
