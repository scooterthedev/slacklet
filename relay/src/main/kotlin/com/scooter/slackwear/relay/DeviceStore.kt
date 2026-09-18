package com.scooter.slackwear.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

internal fun notificationDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun sameDigest(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII),
)

internal fun validUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun validIdentity(value: String): Boolean = value.matches(Regex("[A-Z][A-Z0-9_]{1,79}"))
internal fun validCapability(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

@Serializable
class EnrollmentRequest(
    val teamId: String,
    val deviceId: String,
    val capabilityHash: String,
    val fcmToken: String,
    val slackToken: String,
    val settings: NotificationSettings = NotificationSettings(),
)

@Serializable
data class EnrollmentResponse(val registrationId: String, val teamId: String, val userId: String)

@Serializable
data class DeviceUpdateRequest(val fcmToken: String, val settings: NotificationSettings)

@Serializable
internal data class EnrollmentState(
    val version: Int = 2,
    val devices: List<Device> = emptyList(),
)

internal class EnrollmentFile(directory: String) {
    private val root = Path.of(directory).toAbsolutePath().normalize()
    private val file = root.resolve("notification-enrollment-v2.json")
    private val lock = root.resolve("notification-enrollment-v2.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val permissions = PosixFilePermissions.fromString("rw-------")

    init {
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        require(!Files.isSymbolicLink(root))
    }

    fun <T> transaction(block: (EnrollmentState) -> Pair<EnrollmentState, T>): T = synchronized(processLock) {
        require(!Files.isSymbolicLink(lock) && !Files.isSymbolicLink(file))
        FileChannel.open(lock, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(permissions)).use { channel ->
            channel.lock().use {
                Files.setPosixFilePermissions(lock, permissions)
                val legacy = root.resolve("devices.json")
                if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                    require(!Files.isSymbolicLink(legacy))
                    Files.setPosixFilePermissions(legacy, permissions)
                    Files.move(legacy, root.resolve("devices.legacy-quarantined-${UUID.randomUUID()}.json"), StandardCopyOption.ATOMIC_MOVE)
                }
                val state = if (Files.exists(file)) {
                    Files.setPosixFilePermissions(file, permissions)
                    json.decodeFromString<EnrollmentState>(Files.readString(file)).also { require(it.version == 2) }
                } else EnrollmentState()
                val (next, result) = block(state)
                if (next != state) {
                    val temporary = Files.createTempFile(root, ".enrollment-", ".tmp", PosixFilePermissions.asFileAttribute(permissions))
                    try {
                        FileChannel.open(temporary, StandardOpenOption.WRITE).use { output ->
                            val bytes = ByteBuffer.wrap(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                            while (bytes.hasRemaining()) output.write(bytes)
                            output.force(true)
                        }
                        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
                result
            }
        }
    }

    companion object {
        private val processLock = Any()
    }
}

class DeviceStore(stateDirectory: String) {
    private val file = EnrollmentFile(stateDirectory)

    init {
        file.transaction { it to Unit }
    }

    fun enroll(request: EnrollmentRequest, identity: VerifiedIdentity): EnrollmentResponse? {
        if (!validCapability(request.capabilityHash) || !validIdentity(request.teamId) ||
            !validUuid(request.deviceId) || !validFcmToken(request.fcmToken) || request.teamId !in identity.teamIds
        ) return null
        return file.transaction { state ->
            val existing = state.devices.firstOrNull {
                it.deviceId == request.deviceId && it.teamId == request.teamId && it.slackUserId == identity.userId
            }
            if (existing == null && state.devices.size >= 4096) return@transaction state to null
            val device = existing?.copy(
                teamIds = identity.teamIds,
                fcmToken = request.fcmToken, capabilityHash = request.capabilityHash,
                settings = request.settings, revoked = false,
                tokenGeneration = existing.registrationId + ":" + notificationDigest(request.fcmToken),
            ) ?: Device(
                slackUserId = identity.userId, teamId = request.teamId, teamIds = identity.teamIds,
                fcmToken = request.fcmToken, deviceId = request.deviceId,
                capabilityHash = request.capabilityHash, settings = request.settings,
            )
            state.copy(devices = state.devices.filterNot { it.registrationId == device.registrationId } + device) to device.response()
        }
    }

    fun authenticated(registrationId: String, capability: String): Device? {
        if (!validUuid(registrationId) || !validCapability(capability)) return null
        return all().firstOrNull { it.registrationId == registrationId && sameDigest(it.capabilityHash, notificationDigest(capability)) }
    }

    fun update(registrationId: String, capability: String, request: DeviceUpdateRequest): Boolean {
        if (!validCapability(capability) || !validFcmToken(request.fcmToken)) return false
        return file.transaction { state ->
            val device = state.devices.firstOrNull {
                it.registrationId == registrationId && !it.revoked && sameDigest(it.capabilityHash, notificationDigest(capability))
            } ?: return@transaction state to false
            if (device.teamIds.isEmpty()) return@transaction state to false
            val updated = device.copy(fcmToken = request.fcmToken, settings = request.settings,
                tokenGeneration = device.registrationId + ":" + notificationDigest(request.fcmToken))
            state.copy(devices = state.devices.map { if (it.registrationId == registrationId) updated else it }) to true
        }
    }

    fun revoke(registrationId: String, capability: String): Boolean {
        if (!validCapability(capability)) return false
        return file.transaction { state ->
            val device = state.devices.firstOrNull {
                it.registrationId == registrationId && sameDigest(it.capabilityHash, notificationDigest(capability))
            } ?: return@transaction state to false
            state.copy(devices = state.devices.map {
                if (it.registrationId == device.registrationId) it.copy(revoked = true, fcmToken = "") else it
            }) to true
        }
    }

    internal fun register(device: Device) {
        file.transaction { state ->
            state.copy(devices = state.devices.filterNot { it.registrationId == device.registrationId } + device) to Unit
        }
    }

    internal fun updateSettings(slackUserId: String, settings: NotificationSettings): Boolean = file.transaction { state ->
        val device = state.devices.singleOrNull { it.slackUserId == slackUserId && !it.revoked } ?: return@transaction state to false
        state.copy(devices = state.devices.map { if (it.registrationId == device.registrationId) it.copy(settings = settings) else it }) to true
    }

    fun removeIfCurrent(device: Device): Boolean = file.transaction { state ->
        val current = state.devices.firstOrNull { it.registrationId == device.registrationId && !it.revoked }
        if (current == null || current.tokenGeneration != device.tokenGeneration || current.fcmToken != device.fcmToken) {
            state to false
        } else {
            state.copy(devices = state.devices.map { if (it == current) it.copy(revoked = true, fcmToken = "") else it }) to true
        }
    }

    fun find(slackUserId: String): Device? = all().singleOrNull { it.slackUserId == slackUserId }
    fun findByGeneration(teamId: String, userId: String, generation: String): Device? = all().firstOrNull {
        it.teamId == teamId && it.slackUserId == userId && it.tokenGeneration == generation
    }
    fun forAccount(teamId: String, userId: String): List<Device> =
        all().filter { it.slackUserId == userId && it.answersTo(teamId) }

    fun forAnyTeam(teamIds: Set<String>, userId: String): List<Device> =
        all().filter { device -> device.slackUserId == userId && teamIds.any(device::answersTo) }
    fun all(): List<Device> = file.transaction { state -> state to state.devices.filterNot { it.revoked } }

    private fun Device.response() = EnrollmentResponse(registrationId = registrationId, teamId = teamId, userId = slackUserId)
    private fun validFcmToken(token: String): Boolean = token.length in 1..4096 && token.none { it.isWhitespace() || it.isISOControl() } && !token.startsWith("xox", ignoreCase = true)
}
