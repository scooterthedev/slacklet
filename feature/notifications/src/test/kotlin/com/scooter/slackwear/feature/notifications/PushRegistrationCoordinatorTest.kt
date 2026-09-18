package com.scooter.slackwear.feature.notifications

import com.scooter.slackwear.core.model.repository.NotificationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationCoordinatorTest {
    private class Fixture {
        val sessions = MutableStateFlow<String?>("synthetic-session")
        var settings = NotificationSettings()
        val calls = mutableListOf<Pair<String, NotificationSettings>>()
        var action: suspend () -> Result<Unit> = { Result.success(Unit) }
        val coordinator = PushRegistrationCoordinator(
            sessions = sessions,
            currentSession = { sessions.value },
            isValidSession = { it.isNotBlank() },
            currentSettings = { settings },
            register = { session, settings ->
                calls += session to settings
                action()
            },
        )
    }

    @Test
    fun foregroundWithSessionRegistersOnce() = runTest {
        val fixture = Fixture()
        val foreground = backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertEquals(listOf("synthetic-session" to fixture.settings), fixture.calls)
        assertEquals(PushRegistrationStatus.Registered, fixture.coordinator.status.value)
        fixture.sessions.value = "synthetic-session"
        runCurrent()
        assertEquals(1, fixture.calls.size)
        foreground.cancelAndJoin()
    }

    @Test
    fun foregroundWithoutSessionDoesNothingThenSignInRegisters() = runTest {
        val fixture = Fixture()
        fixture.sessions.value = null
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        assertEquals(PushRegistrationStatus.NoSession, fixture.coordinator.status.value)
        fixture.sessions.value = "synthetic-signin"
        runCurrent()
        assertEquals(listOf("synthetic-signin" to fixture.settings), fixture.calls)
    }

    @Test
    fun invalidSessionDoesNotRegister() = runTest {
        val fixture = Fixture()
        fixture.sessions.value = " "
        fixture.coordinator.registerCurrent()
        assertTrue(fixture.calls.isEmpty())
    }

    @Test
    fun eachForegroundReadsLatestSettings() = runTest {
        val fixture = Fixture()
        val first = launch { fixture.coordinator.whileForeground() }
        runCurrent()
        first.cancelAndJoin()
        fixture.settings = NotificationSettings(
            pushToWatch = false,
            mentions = false,
            directMessages = false,
            threadReplies = false,
            allActivity = true,
            followSlackDnd = false,
        )
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertEquals(2, fixture.calls.size)
        assertEquals(fixture.settings, fixture.calls.last().second)
    }

    @Test
    fun concurrentForegroundAndRotationAreCoalesced() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.action = { gate.await(); Result.success(Unit) }
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        repeat(10) { launch { fixture.coordinator.registerCurrent() } }
        runCurrent()
        assertEquals(1, fixture.calls.size)
        assertEquals(PushRegistrationStatus.Registering, fixture.coordinator.status.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.calls.size)
        assertEquals(PushRegistrationStatus.Registered, fixture.coordinator.status.value)
    }

    @Test
    fun failureIsSanitizedAndRetriesOnlyOnNextForeground() = runTest {
        val fixture = Fixture()
        fixture.action = { Result.failure(IllegalStateException("synthetic-secret/user/query")) }
        val first = launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertEquals(PushRegistrationStatus.Failed, fixture.coordinator.status.value)
        runCurrent()
        assertEquals(1, fixture.calls.size)
        first.cancelAndJoin()
        fixture.action = { Result.success(Unit) }
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertEquals(2, fixture.calls.size)
        assertEquals(PushRegistrationStatus.Registered, fixture.coordinator.status.value)
    }

    @Test
    fun thrownFailureDoesNotStopSessionObservation() = runTest {
        val fixture = Fixture()
        fixture.action = { throw IllegalStateException("synthetic-secret") }
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertEquals(PushRegistrationStatus.Failed, fixture.coordinator.status.value)
        fixture.sessions.value = null
        runCurrent()
        fixture.action = { Result.success(Unit) }
        fixture.sessions.value = "synthetic-signin"
        runCurrent()
        assertEquals(2, fixture.calls.size)
        assertEquals(PushRegistrationStatus.Registered, fixture.coordinator.status.value)
    }

    @Test
    fun backgroundCancellationReleasesRegistrationForNextForeground() = runTest {
        val fixture = Fixture()
        var cancelled = false
        fixture.action = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val first = launch { fixture.coordinator.whileForeground() }
        runCurrent()
        first.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(PushRegistrationStatus.Cancelled, fixture.coordinator.status.value)
        fixture.action = { Result.success(Unit) }
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun cancellationInResultIsRethrownNotReportedAsFailure() = runTest {
        val fixture = Fixture()
        fixture.action = { Result.failure(CancellationException("synthetic-secret")) }
        var caught = false
        try {
            fixture.coordinator.registerCurrent()
        } catch (_: CancellationException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(PushRegistrationStatus.Cancelled, fixture.coordinator.status.value)
        fixture.action = { Result.success(Unit) }
        fixture.coordinator.registerCurrent()
        assertEquals(2, fixture.calls.size)
    }

    @Test
    fun foregroundWaitingOnCancelledRotationStillRegisters() = runTest {
        val fixture = Fixture()
        fixture.action = { awaitCancellation() }
        val rotation = launch { fixture.coordinator.registerCurrent() }
        runCurrent()
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        fixture.action = { Result.success(Unit) }
        rotation.cancelAndJoin()
        runCurrent()
        assertEquals(2, fixture.calls.size)
        assertEquals(PushRegistrationStatus.Registered, fixture.coordinator.status.value)
    }

    @Test
    fun concurrentFailureIsNotRetriedByWaitingTriggers() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.action = { gate.await(); Result.failure(IllegalStateException("synthetic-secret")) }
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        repeat(10) { launch { fixture.coordinator.registerCurrent() } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.calls.size)
        assertEquals(PushRegistrationStatus.Failed, fixture.coordinator.status.value)
    }

    @Test
    fun sessionChangedWhileWaitingRegistersNewSession() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.action = { gate.await(); Result.success(Unit) }
        launch { fixture.coordinator.registerCurrent() }
        runCurrent()
        fixture.sessions.value = "synthetic-new-session"
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("synthetic-session", "synthetic-new-session"), fixture.calls.map { it.first })
        assertEquals(PushRegistrationStatus.Registered, fixture.coordinator.status.value)
    }

    @Test
    fun settingsReadFailureIsSanitizedAndCanRetry() = runTest {
        var failSettings = true
        var registrations = 0
        val sessions = MutableStateFlow<String?>("synthetic-session")
        val coordinator = PushRegistrationCoordinator(
            sessions = sessions,
            currentSession = { sessions.value },
            isValidSession = { it.isNotBlank() },
            currentSettings = {
                if (failSettings) error("synthetic-secret")
                NotificationSettings()
            },
            register = { _, _ -> registrations++; Result.success(Unit) },
        )
        coordinator.registerCurrent()
        assertEquals(PushRegistrationStatus.Failed, coordinator.status.value)
        assertEquals(0, registrations)
        failSettings = false
        coordinator.registerCurrent()
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)
        assertEquals(1, registrations)
    }

    @Test
    fun signOutCancelsInFlightRegistration() = runTest {
        val fixture = Fixture()
        var cancelled = false
        fixture.action = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        backgroundScope.launch { fixture.coordinator.whileForeground() }
        runCurrent()
        fixture.sessions.value = null
        runCurrent()
        assertTrue(cancelled)
        assertEquals(PushRegistrationStatus.NoSession, fixture.coordinator.status.value)
        assertEquals(1, fixture.calls.size)
    }
}
