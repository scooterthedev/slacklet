package com.scooter.slackwear.relay

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DurableDeliveryStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private var time = 0L
    private val path get() = directory.root.absolutePath
    private val file get() = directory.root.toPath().resolve("deliveries.json")
    private fun store(capacity: Int = 4096, ttl: Long = 3_600_000) =
        DurableDeliveryStore(path, capacity, ttl, { time })
    private fun delivery(id: String = "E1", generation: String = "synthetic-generation") = Delivery(
        DeliveryKey(id, "T1", "U1", generation),
        mapOf("kind" to "DIRECT_MESSAGE", "channelId" to "D1", "preview" to "synthetic preview"),
    )
    private val dnd = object : DndChecker {
        override fun isSnoozed(device: Device) = false
    }
    private val sender = object : PushSender {
        override val isReady = true
        override fun send(device: Device, payload: Map<String, String>) = PushOutcome.Accepted
    }

    @Test
    fun `pending backoff and all terminal keys restore without preview`() {
        store().use { store ->
            DeliveryState.entries.forEach { state ->
                store.enqueue(listOf(delivery(state.name)), time)
                val entry = store.due(time)!!
                store.update(entry.copy(state = state, attempts = 2, nextAttemptAt = 500))
            }
        }
        store().use { store ->
            assertEquals(DeliveryState.entries.size, store.snapshot().size)
            val pending = store.snapshot().single { it.state == DeliveryState.Pending }
            assertEquals(2, pending.attempts)
            assertEquals(500L, pending.nextAttemptAt)
            assertNull(store.due(499))
            assertEquals(pending, store.due(500))
            store.snapshot().filter { it.state != DeliveryState.Pending }.forEach {
                assertTrue(it.delivery.payload.isEmpty())
            }
            DeliveryState.entries.forEach { assertTrue(store.enqueue(listOf(delivery(it.name)), 1)) }
            assertEquals(DeliveryState.entries.size, store.snapshot().size)
        }
    }

    @Test
    fun `ttl prunes pending and terminal at startup and persists pruning`() {
        store(ttl = 100).use {
            it.enqueue(listOf(delivery(), delivery("E2")), 0)
            it.update(it.due(0)!!.copy(state = DeliveryState.Accepted))
        }
        time = 100
        store(ttl = 100).use { assertTrue(it.snapshot().isEmpty()) }
        time = 0
        store(ttl = 100).use { assertTrue(it.snapshot().isEmpty()) }
    }

    @Test
    fun `capacity includes terminal keys and rejects whole batch across restart`() {
        store(capacity = 1, ttl = 100).use {
            assertFalse(it.enqueue(listOf(delivery(), delivery("E2")), 0))
            assertTrue(it.snapshot().isEmpty())
            assertTrue(it.enqueue(listOf(delivery()), 0))
            it.update(it.due(0)!!.copy(state = DeliveryState.Accepted))
        }
        store(capacity = 1, ttl = 100).use {
            assertTrue(it.enqueue(listOf(delivery()), 1))
            assertFalse(it.enqueue(listOf(delivery("E2")), 1))
            assertTrue(it.enqueue(listOf(delivery("E2")), 100))
            assertEquals("E2", it.snapshot().single().delivery.key.eventId)
        }
    }

    @Test
    fun `default capacity is 4096 and ttl is one hour`() {
        store().use {
            assertTrue(it.enqueue((1..4096).map { id -> delivery("E$id") }, 0))
            assertFalse(it.enqueue(listOf(delivery("overflow")), 1))
            assertEquals(4096, it.snapshot().size)
            assertEquals(3_600_000L, it.snapshot().first().expiresAt)
            assertNull(it.nextAttemptAt(3_600_000))
            assertTrue(it.snapshot().isEmpty())
        }
    }

    @Test
    fun `write fsync and rename failures preserve memory disk and retry admission`() {
        var failure: DurableDeliveryStore.WriteStage? = null
        DurableDeliveryStore(path, 4096, 100, { time }) { stage ->
            if (stage == failure) throw IOException("synthetic write failure")
        }.use { store ->
            store.enqueue(listOf(delivery()), 0)
            for (stage in listOf(DurableDeliveryStore.WriteStage.Write, DurableDeliveryStore.WriteStage.FileSync,
                DurableDeliveryStore.WriteStage.Rename)) {
                val before = store.snapshot()
                val bytes = Files.readAllBytes(file)
                failure = stage
                assertThrows(IOException::class.java) { store.enqueue(listOf(delivery("E2")), 0) }
                assertThrows(IOException::class.java) { store.update(before.single().copy(state = DeliveryState.Accepted)) }
                assertThrows(IOException::class.java) { store.due(100) }
                assertThrows(IOException::class.java) { store.nextAttemptAt(100) }
                assertEquals(before, store.snapshot())
                assertArrayEquals(bytes, Files.readAllBytes(file))
                failure = null
            }
            assertTrue(store.enqueue(listOf(delivery("E2")), 0))
        }
        store(ttl = 100).use { assertEquals(2, it.snapshot().size) }
    }

    @Test
    fun `post rename fsync failure prevents ack and further use until reopened`() {
        var fail = false
        DurableDeliveryStore(path, 4096, 3_600_000, { time }) {
            if (fail && it == DurableDeliveryStore.WriteStage.DirectorySync) throw IOException("synthetic fsync failure")
        }.use {
            fail = true
            assertThrows(IOException::class.java) { it.enqueue(listOf(delivery()), 0) }
            assertThrows(IllegalStateException::class.java) { it.enqueue(listOf(delivery()), 0) }
            assertThrows(IllegalStateException::class.java) { it.due(0) }
        }
        store().use {
            assertTrue(it.enqueue(listOf(delivery()), 0))
            assertEquals(1, it.snapshot().size)
        }
    }

    @Test
    fun `single writer lock excludes other instances and close releases it`() {
        val first = store()
        assertThrows(Exception::class.java) { store() }
        first.enqueue(listOf(delivery()), 0)
        first.close()
        first.close()
        assertThrows(IllegalStateException::class.java) { first.enqueue(emptyList(), 0) }
        store().use { assertEquals(1, it.snapshot().size) }
    }

    @Test
    fun `truncated corrupt and unsupported stores fail closed and release lock`() {
        store().use { it.enqueue(listOf(delivery()), 0) }
        val valid = Files.readString(file)
        for (corrupt in listOf("", valid.take(valid.length / 2), valid.replace("\"version\":1", "\"version\":2"))) {
            Files.writeString(file, corrupt)
            repeat(2) {
                assertThrows(Exception::class.java) { store() }
                assertEquals(corrupt, Files.readString(file))
            }
        }
        Files.writeString(file, valid)
        store().use { assertEquals(1, it.snapshot().size) }
    }

    @Test
    fun `payload is immutable and credentials cannot be added to queue fields`() {
        val mutable = delivery().payload.toMutableMap()
        store().use {
            it.enqueue(listOf(delivery().copy(payload = mutable)), 0)
            mutable["preview"] = "mutated"
            val entry = it.due(0)!!
            assertEquals("synthetic preview", entry.delivery.payload["preview"])
            assertThrows(UnsupportedOperationException::class.java) {
                (entry.delivery.payload as MutableMap)["preview"] = "mutated"
            }
            for (key in listOf("fcmToken", "slackUserToken", "capability", "capabilityHash")) {
                assertThrows(IllegalArgumentException::class.java) {
                    it.enqueue(listOf(delivery(key).copy(payload = delivery().payload + (key to "synthetic-secret"))), 0)
                }
            }
            assertEquals(1, it.snapshot().size)
        }
        assertFalse(Files.readString(file).contains("synthetic-secret"))
    }

    @Test
    fun `restored job resolves generation for multiple devices and stores no device credentials`() = runTest {
        val devices = DeviceStore(path)
        val device = Device("U1", "T1", "synthetic-fcm-token", capabilityHash = "synthetic-capability-hash")
        devices.register(device)
        devices.register(Device("U1", "T1", "synthetic-other-token"))
        val job = delivery(generation = device.tokenGeneration)
        store().use { it.enqueue(listOf(job), 0) }
        val text = Files.readString(file)
        for (secret in listOf(device.fcmToken, device.capabilityHash, device.tokenGeneration)) {
            assertFalse(text.contains(secret))
        }
        assertTrue(text.contains(generationDigest(device.tokenGeneration)))
        var sends = 0
        val fake = object : PushSender {
            override val isReady = true
            override fun send(device: Device, payload: Map<String, String>): PushOutcome {
                assertEquals("synthetic-fcm-token", device.fcmToken)
                sends++
                return PushOutcome.Accepted
            }
        }
        store().use {
            assertTrue(DeliveryService(devices, fake, dnd, it, { time }).processNext())
            assertEquals(DeliveryState.Accepted, it.snapshot().single().state)
        }
        assertFalse(Files.readString(file).contains("synthetic preview"))
        store().use {
            val service = DeliveryService(devices, fake, dnd, it, { time })
            assertTrue(service.enqueue(listOf(job)))
            assertFalse(service.processNext())
        }
        assertEquals(1, sends)
    }

    @Test
    fun `directory snapshot and lock permissions are restrictive`() {
        store().use { it.enqueue(listOf(delivery()), 0) }
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory.root.toPath()))
        for (name in listOf("deliveries.json", "deliveries.lock")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(directory.root.toPath().resolve(name)))
        }
    }

    @Test
    fun `signed callback write failure returns retry status instead of ack`() {
        var fail = false
        var failedWrites = 0
        DurableDeliveryStore(path, 4096, 3_600_000, System::currentTimeMillis) {
            if (fail) {
                failedWrites++
                throw IOException("synthetic persistence failure")
            }
        }.use { store ->
            DeviceStore(path).register(Device("U1", "T1", "synthetic-token"))
            fail = true
            testApplication {
                application { relayModule(Config(0, "synthetic-signing-key", null, path), sender, store, dnd) }
                val body = """{"type":"event_callback","team_id":"T1","event_id":"E1","authorizations":[{"user_id":"U1","team_id":"T1","is_bot":false}],"event":{"type":"message","user":"U2","text":"hello","ts":"1.2","channel":"D1","channel_type":"im"}}"""
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec("synthetic-signing-key".toByteArray(), "HmacSHA256"))
                val signature = "v0=" + mac.doFinal("v0:$timestamp:$body".toByteArray()).joinToString("") { "%02x".format(it) }
                val response = client.post("/slack/events") {
                    header("X-Slack-Request-Timestamp", timestamp)
                    header("X-Slack-Signature", signature)
                    setBody(body)
                }
                assertTrue(response.status == HttpStatusCode.InternalServerError || response.status == HttpStatusCode.ServiceUnavailable)
                assertEquals(1, failedWrites)
                assertTrue(store.snapshot().isEmpty())
            }
        }
    }
}
