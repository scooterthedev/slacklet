package com.scooter.slackwear.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

class DurableDeliveryStore internal constructor(
    stateDirectory: String,
    private val capacity: Int,
    private val ttlMillis: Long,
    private val now: () -> Long,
    private val beforeWrite: (WriteStage) -> Unit,
) : DeliveryStore, Closeable {
    constructor(
        stateDirectory: String,
        capacity: Int = 4096,
        ttlMillis: Long = 3_600_000,
        now: () -> Long = System::currentTimeMillis,
    ) : this(stateDirectory, capacity, ttlMillis, now, {})

    internal enum class WriteStage { Write, FileSync, Rename, DirectorySync }

    @Serializable
    private data class Snapshot(val version: Int, val entries: List<DeliveryEntry>)

    private val directory = Path.of(stateDirectory).toAbsolutePath().normalize()
    private val file = directory.resolve("deliveries.json")
    private val permissions = PosixFilePermissions.fromString("rw-------")
    private val json = Json { encodeDefaults = true }
    private val lockChannel: FileChannel
    private val lock: FileLock
    private var entries = linkedMapOf<DeliveryKey, DeliveryEntry>()
    private var closed = false
    private var uncertain = false

    init {
        require(capacity in 1..4096 && ttlMillis in 1..3_600_000)
        createDirectory(directory)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS))
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        val lockPath = directory.resolve("deliveries.lock")
        lockChannel = FileChannel.open(
            lockPath, setOf(CREATE, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(permissions),
        )
        try {
            lock = lockChannel.tryLock() ?: throw IOException("Delivery store is already locked")
            Files.setPosixFilePermissions(lockPath, permissions)
            Files.newDirectoryStream(directory, "deliveries-*.tmp").use { paths ->
                paths.forEach { Files.delete(it) }
            }
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Invalid delivery store file" }
                Files.setPosixFilePermissions(file, permissions)
                require(Files.size(file) <= MAX_BYTES) { "Delivery store is too large" }
                val snapshot = json.decodeFromString<Snapshot>(Files.readString(file))
                require(snapshot.version == 1 && snapshot.entries.size <= 4096) { "Invalid delivery store format" }
                snapshot.entries.forEach { entry ->
                    validate(entry)
                    require(entries.put(entry.delivery.key, immutable(entry)) == null) { "Duplicate delivery key" }
                }
                val retained = retained(now())
                require(retained.size <= capacity) { "Delivery store exceeds capacity" }
                commit(retained)
            } else {
                persist(entries)
            }
            syncDirectory(directory)
        } catch (failure: Throwable) {
            lockChannel.close()
            throw failure
        }
    }

    @Synchronized
    override fun enqueue(deliveries: List<Delivery>, now: Long): Boolean {
        checkOpen()
        val candidate = retained(now)
        val additions = deliveries.map { delivery ->
            delivery.copy(key = delivery.key.copy(generation = digest(delivery.key.generation)))
        }.distinctBy { it.key }.filterNot { it.key in candidate }
        if (additions.size > capacity - candidate.size) {
            commit(candidate)
            return false
        }
        additions.forEach { delivery ->
            val entry = immutable(DeliveryEntry(delivery, Math.addExact(now, ttlMillis), now))
            validate(entry)
            candidate[delivery.key] = entry
        }
        commit(candidate)
        return true
    }

    @Synchronized
    override fun due(now: Long): DeliveryEntry? {
        checkOpen()
        commit(retained(now))
        return entries.values.firstOrNull { it.state == DeliveryState.Pending && it.nextAttemptAt <= now }
    }

    @Synchronized
    override fun update(entry: DeliveryEntry) {
        checkOpen()
        val key = entry.delivery.key.copy(generation = digest(entry.delivery.key.generation))
        val current = entries[key] ?: return
        if (current.expiresAt != entry.expiresAt || current.state != DeliveryState.Pending) return
        val updated = immutable(entry.copy(delivery = entry.delivery.copy(
            key = key,
            payload = if (entry.state == DeliveryState.Pending) entry.delivery.payload else emptyMap(),
        )))
        validate(updated)
        val candidate = LinkedHashMap(entries)
        candidate[key] = updated
        commit(candidate)
    }

    @Synchronized
    override fun nextAttemptAt(now: Long): Long? {
        checkOpen()
        commit(retained(now))
        return entries.values.minOfOrNull {
            if (it.state == DeliveryState.Pending) minOf(it.nextAttemptAt, it.expiresAt) else it.expiresAt
        }
    }

    @Synchronized
    fun snapshot(): List<DeliveryEntry> {
        checkOpen()
        return entries.values.toList()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            lock.release()
        } finally {
            lockChannel.close()
        }
    }

    private fun checkOpen() {
        check(!closed) { "Delivery store is closed" }
        check(!uncertain) { "Delivery store must be reopened after an uncertain durable write" }
    }

    private fun retained(now: Long) = LinkedHashMap(entries.filterValues { it.expiresAt > now })

    private fun commit(candidate: LinkedHashMap<DeliveryKey, DeliveryEntry>) {
        if (candidate == entries) return
        persist(candidate)
        entries = candidate
    }

    private fun persist(candidate: Map<DeliveryKey, DeliveryEntry>) {
        val bytes = json.encodeToString(Snapshot(1, candidate.values.toList())).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Delivery store is too large" }
        val temporary = Files.createTempFile(directory, "deliveries-", ".tmp", PosixFilePermissions.asFileAttribute(permissions))
        var renamed = false
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                beforeWrite(WriteStage.Write)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                beforeWrite(WriteStage.FileSync)
                channel.force(true)
            }
            beforeWrite(WriteStage.Rename)
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            renamed = true
            beforeWrite(WriteStage.DirectorySync)
            syncDirectory(directory)
        } catch (failure: Throwable) {
            if (renamed) uncertain = true
            throw failure
        } finally {
            if (!renamed) Files.deleteIfExists(temporary)
        }
    }

    private fun immutable(entry: DeliveryEntry) = entry.copy(delivery = entry.delivery.copy(
        payload = java.util.Collections.unmodifiableMap(LinkedHashMap(entry.delivery.payload)),
    ))

    private fun digest(generation: String) =
        if (DIGEST.matches(generation)) generation else generationDigest(generation)

    private fun validate(entry: DeliveryEntry) {
        val key = entry.delivery.key
        require(listOf(key.eventId, key.teamId, key.userId).all { it.isNotBlank() && it.length <= 512 })
        require(DIGEST.matches(key.generation)) { "Invalid delivery generation digest" }
        require(entry.attempts in 0..8)
        val payload = entry.delivery.payload
        if (entry.state == DeliveryState.Pending) {
            require(payload.keys.all { it in PAYLOAD_KEYS } && payload.values.all { it.length <= 4096 })
            require(payload["kind"] in PushKind.entries.map { it.name })
            require((payload["preview"]?.length ?: 0) <= 120)
        } else {
            require(payload.isEmpty()) { "Terminal delivery contains payload" }
        }
    }

    private fun createDirectory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) return
        val parent = requireNotNull(path.parent)
        createDirectory(parent)
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        syncDirectory(parent)
    }

    private fun syncDirectory(path: Path) {
        FileChannel.open(path, READ).use { it.force(true) }
    }

    private companion object {
        const val MAX_BYTES = 128L * 1024 * 1024
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val PAYLOAD_KEYS = setOf(
            "kind", "channelId", "ts", "authorId", "threadTs", "preview",
            "v", "eventId", "registrationId", "teamId", "slackUserId", "expiresAt",
        )
    }
}
