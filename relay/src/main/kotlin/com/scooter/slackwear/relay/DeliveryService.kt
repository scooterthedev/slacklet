package com.scooter.slackwear.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.random.Random

@Serializable
data class DeliveryKey(val eventId: String, val teamId: String, val userId: String, val generation: String)

internal fun generationDigest(generation: String): String =
    "sha256:" + MessageDigest.getInstance("SHA-256").digest(generation.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

@Serializable
data class Delivery(val key: DeliveryKey, val payload: Map<String, String>)

@Serializable
enum class DeliveryState { Pending, Accepted, InvalidToken, PermanentFailure, Exhausted, Suppressed }

@Serializable
data class DeliveryEntry(
    val delivery: Delivery,
    val expiresAt: Long,
    val nextAttemptAt: Long,
    val attempts: Int = 0,
    val state: DeliveryState = DeliveryState.Pending,
)

interface DeliveryStore {
    fun enqueue(deliveries: List<Delivery>, now: Long): Boolean
    fun due(now: Long): DeliveryEntry?
    fun update(entry: DeliveryEntry)
    fun nextAttemptAt(now: Long): Long?
}

class InMemoryDeliveryStore(
    private val capacity: Int = 4096,
    private val ttlMillis: Long = 3_600_000,
) : DeliveryStore {
    private val entries = LinkedHashMap<DeliveryKey, DeliveryEntry>()

    init {
        require(capacity > 0 && ttlMillis > 0)
    }

    @Synchronized
    override fun enqueue(deliveries: List<Delivery>, now: Long): Boolean {
        expire(now)
        val additions = deliveries.distinctBy { it.key }.filterNot { it.key in entries }
        if (additions.size > capacity - entries.size) return false
        additions.forEach {
            entries[it.key] = DeliveryEntry(it.copy(payload = it.payload.toMap()), now + ttlMillis, now)
        }
        return true
    }

    @Synchronized
    override fun due(now: Long): DeliveryEntry? {
        expire(now)
        return entries.values.firstOrNull { it.state == DeliveryState.Pending && it.nextAttemptAt <= now }
    }

    @Synchronized
    override fun update(entry: DeliveryEntry) {
        if (entries[entry.delivery.key]?.expiresAt == entry.expiresAt) {
            entries[entry.delivery.key] = if (entry.state == DeliveryState.Pending) entry
            else entry.copy(delivery = entry.delivery.copy(payload = emptyMap()))
        }
    }

    @Synchronized
    override fun nextAttemptAt(now: Long): Long? {
        expire(now)
        return entries.values.minOfOrNull {
            if (it.state == DeliveryState.Pending) minOf(it.nextAttemptAt, it.expiresAt) else it.expiresAt
        }
    }

    @Synchronized
    fun snapshot(): List<DeliveryEntry> = entries.values.toList()

    private fun expire(now: Long) {
        entries.entries.removeIf { it.value.expiresAt <= now }
    }
}

class DeliveryService(
    private val devices: DeviceStore,
    private val sender: PushSender,
    private val dnd: DndChecker,
    private val store: DeliveryStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val processing = Mutex()

    fun enqueue(deliveries: List<Delivery>): Boolean {
        val accepted = store.enqueue(deliveries, now())
        if (accepted) wake.trySend(Unit)
        return accepted
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (processNext()) continue
            val next = store.nextAttemptAt(now())
            if (next == null) wake.receive()
            else withTimeoutOrNull((next - now()).coerceAtLeast(1)) { wake.receive() }
        }
    }

    suspend fun processNext(): Boolean = processing.withLock {
        val entry = store.due(now()) ?: return@withLock false
        val key = entry.delivery.key
        val device = devices.forAccount(key.teamId, key.userId).singleOrNull {
            it.tokenGeneration == key.generation || generationDigest(it.tokenGeneration) == key.generation
        }
        val kind = PushKind.valueOf(entry.delivery.payload.getValue("kind"))
        if (device == null || !device.settings.allows(kind)) {
            store.update(entry.copy(state = DeliveryState.Suppressed))
            return@withLock true
        }
        val outcome = try {
            withContext(Dispatchers.IO) {
                if (device.settings.followSlackDnd && dnd.isSnoozed(device)) null
                else sender.send(device, entry.delivery.payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PushOutcome.Retryable
        }
        val attempts = entry.attempts + 1
        val state = when (outcome) {
            null -> DeliveryState.Suppressed
            PushOutcome.Accepted -> DeliveryState.Accepted
            PushOutcome.InvalidToken -> {
                withContext(Dispatchers.IO) { devices.removeIfCurrent(device) }
                DeliveryState.InvalidToken
            }
            PushOutcome.PermanentFailure -> DeliveryState.PermanentFailure
            PushOutcome.Retryable -> if (attempts >= 8) DeliveryState.Exhausted else DeliveryState.Pending
        }
        val backoff = (1000L shl (attempts - 1).coerceAtMost(6)).coerceAtMost(60_000)
        val delay = (backoff * (0.5 + jitter().coerceIn(0.0, 1.0))).toLong()
        store.update(entry.copy(attempts = attempts, state = state, nextAttemptAt = now() + delay))
        true
    }
}
