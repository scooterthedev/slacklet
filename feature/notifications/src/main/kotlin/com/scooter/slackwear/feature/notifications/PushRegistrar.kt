package com.scooter.slackwear.feature.notifications

import com.google.firebase.messaging.FirebaseMessaging
import com.scooter.slackwear.core.model.repository.NotificationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PushRegistrar(
    private val relay: RelayApi,
    private val bindings: RelayBindingStore,
    private val currentAccount: () -> NotificationAccount?,
    private val currentSlackToken: () -> String?,
    private val currentFcmToken: suspend () -> String = ::firebaseToken,
) {
    private val mutex = Mutex()

    suspend fun register(settings: NotificationSettings): Result<Unit> = relayResult {
        mutex.withLock {
            val account = currentAccount() ?: throw NoAccountException()
            val fcmToken = withTimeout(FCM_TOKEN_TIMEOUT_MILLIS) { currentFcmToken() }
            val existing = bindings.current()?.takeIf { it.matches(account) && it.registrationId != null }
            if (existing != null && refresh(existing, fcmToken, settings)) return@withLock
            enroll(account, fcmToken, settings)
        }
    }

    suspend fun revoke(binding: RelayBinding?): Result<Unit> {
        val registrationId = binding?.registrationId ?: return Result.success(Unit)
        try {
            relay.revoke(registrationId, bearer(binding.capability))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
        return Result.success(Unit)
    }

    private suspend fun refresh(binding: RelayBinding, fcmToken: String, settings: NotificationSettings): Boolean {
        val registrationId = binding.registrationId ?: return false
        return try {
            relay.update(registrationId, bearer(binding.capability), DeviceUpdateRequest(fcmToken, settings.toRelay()))
            true
        } catch (http: HttpException) {
            if (http.code() in FORGOTTEN_BY_RELAY) false else throw http
        }
    }

    private suspend fun enroll(account: NotificationAccount, fcmToken: String, settings: NotificationSettings) {
        val slackToken = currentSlackToken()?.takeIf { it.isNotBlank() } ?: throw NoAccountException()
        val binding = newRelayBinding(account)
        val response = relay.enroll(
            EnrollmentRequest(
                account.teamId, binding.deviceId, capabilityDigest(binding.capability),
                fcmToken, slackToken, settings.toRelay(),
            ),
        )
        require(response.teamId == account.teamId && response.userId == account.userId)
        require(runCatching { UUID.fromString(response.registrationId).toString() == response.registrationId }.getOrDefault(false))
        if (currentAccount() != account) {
            runCatching { relay.revoke(response.registrationId, bearer(binding.capability)) }
            error("Account changed")
        }
        bindings.save(binding.copy(registrationId = response.registrationId))
    }

    private fun bearer(capability: String) = "Bearer $capability"
}

private const val FCM_TOKEN_TIMEOUT_MILLIS = 10_000L
private val FORGOTTEN_BY_RELAY = setOf(401, 404, 410)

class NoAccountException : IllegalStateException("No Slack account")

private suspend fun relayResult(block: suspend () -> Unit): Result<Unit> = try {
    block()
    Result.success(Unit)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (noAccount: NoAccountException) {
    Result.failure(noAccount)
} catch (_: Exception) {
    Result.failure(IllegalStateException("Notification relay unavailable"))
}

private suspend fun firebaseToken(): String = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { continuation.resume(it) }
        .addOnFailureListener { continuation.resumeWithException(it) }
}

internal fun NotificationSettings.toRelay() = RelayNotificationSettings(
    pushToWatch, mentions, directMessages, threadReplies, allActivity, followSlackDnd,
)
