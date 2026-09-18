package com.scooter.slackwear.relay

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeliveryServiceTest {
    @get:Rule val directory = TemporaryFolder()
    private var time = 0L
    private val devices by lazy { DeviceStore(directory.root.absolutePath) }
    private val store = InMemoryDeliveryStore()
    private val sent = mutableListOf<Map<String, String>>()
    private val dnd = object : DndChecker {
        override fun isSnoozed(device: Device) = false
    }

    private fun register(token: String = "fcm-secret", team: String = "T1"): Device {
        val existing = devices.find("U1")
        val device = if (existing == null) Device("U1", team, token) else existing.copy(
            teamId = team,
            fcmToken = token,
            tokenGeneration = if (existing.teamId == team && existing.fcmToken == token) existing.tokenGeneration
                else java.util.UUID.randomUUID().toString(),
        )
        devices.register(device)
        return devices.find("U1")!!
    }

    private fun envelope() = SlackEventEnvelope(
        type = "event_callback",
        teamId = "T1",
        eventId = "Ev1",
        authorizations = listOf(Authorization("U1", "T1", false)),
        event = SlackEvent("message", user = "U2", text = "hello", ts = "123.456", channel = "D1", channelType = "im"),
    )

    private fun service(outcome: (Device) -> PushOutcome = { PushOutcome.Accepted }) = DeliveryService(
        devices, object : PushSender {
            override val isReady = true
            override fun send(device: Device, payload: Map<String, String>): PushOutcome {
                sent += payload
                return outcome(device)
            }
        }, dnd, store, { time }, { 0.5 },
    )

    private fun plan(event: SlackEventEnvelope = envelope()) = EventRouter(devices).plan(event)

    @Test
    fun `unavailable Firebase remains pending without pruning`() = runTest {
        val registered = register()
        val sender = FcmPushSender(null as FirebaseMessaging?)
        assertFalse(sender.isReady)
        assertEquals(PushOutcome.Retryable, sender.send(registered, emptyMap()))
        val service = DeliveryService(devices, sender, dnd, store, { time }, { 0.5 })
        service.enqueue(plan())
        service.processNext()
        assertEquals(DeliveryState.Pending, store.snapshot().single().state)
        assertEquals(registered, devices.find("U1"))
    }

    @Test
    fun `transient retry observes backoff and only accepted means accepted`() = runTest {
        register()
        var outcome: PushOutcome = PushOutcome.Retryable
        val service = service { outcome }
        service.enqueue(plan())
        service.processNext()
        assertEquals(1000L, store.snapshot().single().nextAttemptAt)
        assertEquals(DeliveryState.Pending, store.snapshot().single().state)
        assertNotNull(devices.find("U1"))
        assertFalse(service.processNext())
        time = 1000
        outcome = PushOutcome.Accepted
        assertTrue(service.processNext())
        assertEquals(DeliveryState.Accepted, store.snapshot().single().state)
        assertTrue(store.snapshot().single().delivery.payload.isEmpty())
    }

    @Test
    fun `invalid argument is permanent and never prunes`() = runTest {
        val registered = register()
        val service = service { FcmPushSender.outcomeFor(MessagingErrorCode.INVALID_ARGUMENT) }
        service.enqueue(plan())
        service.processNext()
        assertEquals(DeliveryState.PermanentFailure, store.snapshot().single().state)
        assertEquals(registered, devices.find("U1"))
    }

    @Test
    fun `only unregistered maps to invalid token`() {
        MessagingErrorCode.entries.forEach {
            assertEquals(it == MessagingErrorCode.UNREGISTERED, FcmPushSender.outcomeFor(it) == PushOutcome.InvalidToken)
        }
        assertEquals(PushOutcome.Retryable, FcmPushSender.outcomeFor(MessagingErrorCode.UNAVAILABLE))
        assertEquals(PushOutcome.Retryable, FcmPushSender.outcomeFor(MessagingErrorCode.INTERNAL))
        assertEquals(PushOutcome.Retryable, FcmPushSender.outcomeFor(MessagingErrorCode.QUOTA_EXCEEDED))
    }

    @Test
    fun `unregistered removes current generation`() = runTest {
        register()
        val service = service { PushOutcome.InvalidToken }
        service.enqueue(plan())
        service.processNext()
        assertNull(devices.find("U1"))
        assertEquals(DeliveryState.InvalidToken, store.snapshot().single().state)
    }

    @Test
    fun `unregistered cannot remove rotated registration even if token rotates back`() = runTest {
        val original = register()
        val service = service {
            register("replacement")
            register(original.fcmToken)
            PushOutcome.InvalidToken
        }
        service.enqueue(plan())
        service.processNext()
        assertNotEquals(original.tokenGeneration, devices.find("U1")!!.tokenGeneration)
        assertEquals(original.fcmToken, devices.find("U1")!!.fcmToken)
        assertFalse(devices.removeIfCurrent(original))
    }

    @Test
    fun `duplicates pending and accepted are coalesced under concurrent processing`() = runTest {
        register()
        val service = service()
        val deliveries = plan()
        repeat(10) { assertTrue(service.enqueue(deliveries)) }
        List(10) { async { service.processNext() } }.awaitAll()
        service.enqueue(deliveries)
        assertFalse(service.processNext())
        assertEquals(1, sent.size)
    }

    @Test
    fun `cross team missing team bot and missing bot flag never fan out`() {
        register()
        val event = envelope()
        val invalid = listOf(
            event.copy(teamId = "T2"),
            event.copy(teamId = null),
            event.copy(authorizations = listOf(Authorization("U1", "T2", false))),
            event.copy(authorizations = listOf(Authorization("U1", null, false))),
            event.copy(authorizations = listOf(Authorization("U1", "T1", true))),
            event.copy(authorizations = listOf(Authorization("U1", "T1"))),
            event.copy(authorizations = emptyList()),
            event.copy(eventId = null),
        )
        invalid.forEach { assertTrue(plan(it).isEmpty()) }
        register(team = "T2")
        assertTrue(plan(event).isEmpty())
    }

    @Test
    fun `mixed authorizations only route explicitly authorized nonbot account`() {
        register()
        devices.register(Device("U3", "T1", "other"))
        val event = envelope().copy(authorizations = listOf(
            Authorization("U1", "T1", false), Authorization("U1", "T1", false),
            Authorization("U3", "T1", true),
        ))
        assertEquals(listOf("U1"), plan(event).map { it.key.userId })
    }

    @Test
    fun `watch wire fixture preserves legacy keys and excludes registration secrets`() = runTest {
        register()
        val fixture = """{"type":"event_callback","team_id":"T1","event_id":"Ev1","authorizations":[{"user_id":"U1","team_id":"T1","is_bot":false}],"event":{"type":"message","user":"U2","text":"hello","ts":"123.456","channel":"D1","channel_type":"im","thread_ts":"123.000"}}"""
        val service = service()
        service.enqueue(plan(Json.decodeFromString<SlackEventEnvelope>(fixture)))
        service.processNext()
        assertEquals(mapOf(
            "kind" to "DIRECT_MESSAGE", "channelId" to "D1", "ts" to "123.456",
            "authorId" to "U2", "threadTs" to "123.000", "preview" to "hello",
        ), sent.single().filterKeys { it in setOf("kind", "channelId", "ts", "authorId", "threadTs", "preview") })
        assertFalse(sent.toString().contains("secret"))
        assertFalse(store.snapshot().toString().contains("secret"))
        assertEquals(120, plan(envelope().copy(event = envelope().event!!.copy(text = "x".repeat(500))))
            .single().payload.getValue("preview").length)
    }

    @Test
    fun `exception is retryable and blocking sender runs off calling thread`() = runTest {
        register()
        val callingThread = Thread.currentThread()
        val service = service {
            assertNotSame(callingThread, Thread.currentThread())
            throw java.io.IOException("synthetic")
        }
        service.enqueue(plan())
        service.processNext()
        assertEquals(DeliveryState.Pending, store.snapshot().single().state)
        assertNotNull(devices.find("U1"))
    }

    @Test
    fun `retry attempts are bounded and ttl drops pending work`() = runTest {
        register()
        val service = service { PushOutcome.Retryable }
        service.enqueue(plan())
        repeat(8) {
            time = store.snapshot().single().nextAttemptAt
            assertTrue(service.processNext())
        }
        assertEquals(DeliveryState.Exhausted, store.snapshot().single().state)
        assertFalse(service.processNext())
        assertEquals(8, sent.size)
        time = 3_600_000
        service.enqueue(plan(envelope().copy(eventId = "Ev2")))
        time += 3_600_000
        assertFalse(service.processNext())
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `capacity includes tombstones and batch rejection is atomic`() = runTest {
        register()
        val bounded = InMemoryDeliveryStore(capacity = 1, ttlMillis = 100)
        val first = plan().single()
        val second = first.copy(key = first.key.copy(eventId = "Ev2"))
        assertFalse(bounded.enqueue(listOf(first, second), 0))
        assertTrue(bounded.snapshot().isEmpty())
        assertTrue(bounded.enqueue(listOf(first), 0))
        bounded.update(bounded.due(0)!!.copy(state = DeliveryState.Accepted))
        assertTrue(bounded.enqueue(listOf(first), 1))
        assertFalse(bounded.enqueue(listOf(second), 1))
        assertTrue(bounded.enqueue(listOf(second), 100))
    }

    @Test
    fun `rotation and changed settings suppress stale queued delivery`() = runTest {
        register()
        val service = service()
        service.enqueue(plan())
        register("new-token")
        service.processNext()
        assertTrue(sent.isEmpty())
        service.enqueue(plan())
        devices.updateSettings("U1", NotificationSettings(pushToWatch = false))
        service.processNext()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `generation survives device store reload and unchanged registration`() {
        val original = register()
        assertEquals(original.tokenGeneration, register().tokenGeneration)
        assertEquals(original.tokenGeneration, DeviceStore(directory.root.absolutePath).find("U1")!!.tokenGeneration)
    }
}
