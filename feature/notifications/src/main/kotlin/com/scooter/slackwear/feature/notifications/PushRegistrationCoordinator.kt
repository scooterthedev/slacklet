package com.scooter.slackwear.feature.notifications

import com.scooter.slackwear.core.model.repository.NotificationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

class PushRegistrationCoordinator<S : Any>(
    private val sessions: Flow<S?>,
    private val currentSession: () -> S?,
    private val isValidSession: (S) -> Boolean,
    private val currentSettings: suspend () -> NotificationSettings,
    private val register: suspend (S, NotificationSettings) -> Result<Unit>,
) {
    private val registrationMutex = Mutex()
    private val completedAttempts = AtomicLong()
    private var lastAttemptSession: S? = null
    private val _status = MutableStateFlow(PushRegistrationStatus.Idle)
    val status = _status.asStateFlow()

    suspend fun whileForeground() {
        sessions.distinctUntilChanged().collectLatest {
            var backoff = RETRY_INITIAL_MILLIS
            repeat(RETRY_ATTEMPTS) { attempt ->
                registerCurrent()
                if (_status.value != PushRegistrationStatus.Failed) return@collectLatest
                if (attempt < RETRY_ATTEMPTS - 1) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MILLIS)
                }
            }
        }
    }

    suspend fun registerCurrent() {
        currentCoroutineContext().ensureActive()
        val completedBefore = completedAttempts.get()
        val joinedInFlight = !registrationMutex.tryLock()
        if (joinedInFlight) registrationMutex.lock()
        try {
            val session = currentSession()?.takeIf(isValidSession)
            if (session == null) {
                _status.value = PushRegistrationStatus.NoSession
                return
            }
            if (joinedInFlight && completedAttempts.get() != completedBefore && lastAttemptSession == session) {
                return
            }
            try {
                _status.value = PushRegistrationStatus.Registering
                register(session, currentSettings()).getOrThrow()
                currentCoroutineContext().ensureActive()
                _status.value = PushRegistrationStatus.Registered
            } catch (cancelled: CancellationException) {
                _status.value = PushRegistrationStatus.Cancelled
                throw cancelled
            } catch (_: NoAccountException) {
                _status.value = PushRegistrationStatus.NoSession
            } catch (_: Exception) {
                _status.value = PushRegistrationStatus.Failed
            }
            lastAttemptSession = session
            completedAttempts.incrementAndGet()
        } finally {
            registrationMutex.unlock()
        }
    }
}

private const val RETRY_ATTEMPTS = 6
private const val RETRY_INITIAL_MILLIS = 2_000L
private const val RETRY_MAX_MILLIS = 60_000L

enum class PushRegistrationStatus {
    Idle,
    NoSession,
    Registering,
    Registered,
    Failed,
    Cancelled,
}
