package com.scooter.slackwear.feature.notifications

import com.scooter.slackwear.core.model.repository.NotificationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrarTest {
    private class FakeRelay : RelayApi {
        val enrollments = mutableListOf<EnrollmentRequest>()
        val updates = mutableListOf<Pair<String, DeviceUpdateRequest>>()
        val revocations = mutableListOf<String>()
        val order = mutableListOf<String>()
        var onEnroll: suspend () -> EnrollmentResponse = {
            EnrollmentResponse("00000000-0000-0000-0000-000000000001", "synthetic-team", "synthetic-user")
        }
        var onUpdate: suspend () -> Unit = {}
        var onRevoke: suspend () -> Unit = {}

        override suspend fun enroll(request: EnrollmentRequest): EnrollmentResponse {
            enrollments += request
            order += "enroll"
            return onEnroll()
        }

        override suspend fun update(registrationId: String, authorization: String, request: DeviceUpdateRequest) {
            updates += registrationId to request
            order += "update"
            onUpdate()
        }

        override suspend fun revoke(registrationId: String, authorization: String) {
            revocations += registrationId
            order += "revoke"
            onRevoke()
        }

        override suspend fun startAuth(request: AuthStartRequest): AuthStartResponse =
            error("Auth must not be used")

        override suspend fun authStatus(code: String): AuthStatusResponse =
            error("Auth must not be used")
    }

    private class MemoryBindings : RelayBindingStore {
        var value: RelayBinding? = null
        override fun current(): RelayBinding? = value
        override fun save(binding: RelayBinding) {
            value = binding
        }
        override fun clear() {
            value = null
        }
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Unit>(code, "".toResponseBody("text/plain".toMediaType())),
    )

    private fun registrar(
        relay: RelayApi,
        bindings: RelayBindingStore,
        account: NotificationAccount?,
        slackToken: String? = "synthetic-slack-token",
    ) = PushRegistrar(relay, bindings, { account }, { slackToken }, { "synthetic-fcm" })

    private val account = NotificationAccount("synthetic-team", "synthetic-user")

    private fun binding(capability: String = "synthetic-capability", registrationId: String? = "00000000-0000-0000-0000-000000000001") =
        RelayBinding("synthetic-team", "synthetic-user", "synthetic-device", capability, registrationId)

    @Test
    fun firstRegistrationEnrollsWithoutAnyPairingCode() = runTest {
        val relay = FakeRelay()
        val bindings = MemoryBindings()
        assertTrue(registrar(relay, bindings, account).register(NotificationSettings()).isSuccess)
        val request = relay.enrollments.single()
        assertEquals("synthetic-team", request.teamId)
        assertEquals("synthetic-fcm", request.fcmToken)
        assertEquals("synthetic-slack-token", request.slackToken)
        assertEquals(64, request.capabilityHash.length)
        assertFalse(request.toString().contains(bindings.value!!.capability))
        assertEquals("00000000-0000-0000-0000-000000000001", bindings.value!!.registrationId)
    }

    @Test
    fun laterRegistrationsUpdateTheExistingDeviceInsteadOfEnrolling() = runTest {
        val relay = FakeRelay()
        val bindings = MemoryBindings()
        bindings.save(binding())
        assertTrue(registrar(relay, bindings, account).register(NotificationSettings(allActivity = true)).isSuccess)
        assertEquals("00000000-0000-0000-0000-000000000001", relay.updates.single().first)
        assertEquals("synthetic-fcm", relay.updates.single().second.fcmToken)
        assertTrue(relay.updates.single().second.settings.allActivity)
        assertTrue(relay.enrollments.isEmpty() && relay.revocations.isEmpty())
    }

    @Test
    fun aDeviceTheRelayForgotReEnrollsItselfWithoutTheUserDoingAnything() = runTest {
        val relay = FakeRelay()
        relay.onUpdate = { throw httpError(401) }
        val bindings = MemoryBindings()
        bindings.save(binding(capability = "stale-capability"))
        assertTrue(registrar(relay, bindings, account).register(NotificationSettings()).isSuccess)
        assertEquals(listOf("update", "enroll"), relay.order)
        assertEquals("00000000-0000-0000-0000-000000000001", bindings.value!!.registrationId)
        assertFalse(bindings.value!!.capability == "stale-capability")
    }

    @Test
    fun aRelayOutageDoesNotDiscardTheExistingDevice() = runTest {
        val relay = FakeRelay()
        relay.onUpdate = { throw httpError(503) }
        val bindings = MemoryBindings()
        bindings.save(binding())
        val result = registrar(relay, bindings, account).register(NotificationSettings())
        assertEquals("Notification relay unavailable", result.exceptionOrNull()?.message)
        assertTrue(relay.enrollments.isEmpty())
        assertEquals("synthetic-capability", bindings.value!!.capability)
    }

    @Test
    fun aBindingFromAnotherAccountIsNeverReusedAsCapability() = runTest {
        val relay = FakeRelay()
        val bindings = MemoryBindings()
        bindings.save(RelayBinding("synthetic-team", "other-user", "synthetic-device", "other-capability", "00000000-0000-0000-0000-000000000002"))
        assertTrue(registrar(relay, bindings, account).register(NotificationSettings()).isSuccess)
        assertTrue(relay.updates.isEmpty())
        assertEquals("synthetic-user", bindings.value!!.userId)
        assertFalse(bindings.value!!.capability == "other-capability")
    }

    @Test
    fun withoutASlackTokenNothingIsSentToTheRelay() = runTest {
        val relay = FakeRelay()
        val bindings = MemoryBindings()
        val result = registrar(relay, bindings, account, slackToken = null).register(NotificationSettings())
        assertTrue(result.exceptionOrNull() is NoAccountException)
        assertTrue(relay.order.isEmpty())
        assertNull(bindings.value)
    }

    @Test
    fun rejectedEnrollmentRollsBackBinding() = runTest {
        val relay = FakeRelay()
        relay.onEnroll = { error("synthetic-secret/user/query") }
        val bindings = MemoryBindings()
        val result = registrar(relay, bindings, account).register(NotificationSettings())
        assertEquals("Notification relay unavailable", result.exceptionOrNull()?.message)
        assertNull(bindings.value)
    }

    @Test
    fun anEnrollmentForTheWrongIdentityIsRevokedAndNotStored() = runTest {
        val relay = FakeRelay()
        relay.onEnroll = { EnrollmentResponse("00000000-0000-0000-0000-000000000001", "synthetic-team", "other-user") }
        val bindings = MemoryBindings()
        val result = registrar(relay, bindings, account).register(NotificationSettings())
        assertEquals("Notification relay unavailable", result.exceptionOrNull()?.message)
        assertNull(bindings.value)
    }

    @Test
    fun revokeIsBestEffortAndClearsNothingLocally() = runTest {
        val relay = FakeRelay()
        relay.onRevoke = { error("synthetic-secret/user/query") }
        assertTrue(registrar(relay, MemoryBindings(), null).revoke(binding()).isSuccess)
        assertEquals("00000000-0000-0000-0000-000000000001", relay.revocations.single())
    }

    @Test
    fun cancellingFirebaseWaitNeverCallsRelay() = runTest {
        val relay = FakeRelay()
        val bindings = MemoryBindings()
        val registrar = PushRegistrar(relay, bindings, { account }, { "synthetic-slack-token" }) { awaitCancellation() }
        val job = launch {
            registrar.register(NotificationSettings())
            error("Cancellation was swallowed")
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(relay.order.isEmpty())
        assertNull(bindings.value)
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        val relay = FakeRelay()
        val cancellation = CancellationException("synthetic-secret")
        relay.onEnroll = { throw cancellation }
        var caught = false
        try {
            registrar(relay, MemoryBindings(), account).register(NotificationSettings())
        } catch (exception: CancellationException) {
            caught = true
            assertTrue(exception === cancellation)
        }
        assertTrue(caught)
    }

    @Test
    fun foregroundCoordinatorAndRegistrarUseOnlySyntheticDependencies() = runTest {
        val relay = FakeRelay()
        val bindings = MemoryBindings()
        var settings = NotificationSettings()
        val coordinator = PushRegistrationCoordinator(
            sessions = kotlinx.coroutines.flow.MutableStateFlow("synthetic-session"),
            currentSession = { "synthetic-session" },
            isValidSession = { it.isNotBlank() },
            currentSettings = { settings },
            register = { _, latest -> registrar(relay, bindings, account).register(latest) },
        )
        bindings.save(binding())
        val firstForeground = launch { coordinator.whileForeground() }
        runCurrent()
        assertEquals(1, relay.updates.size)
        firstForeground.cancelAndJoin()
        settings = settings.copy(pushToWatch = false, followSlackDnd = false)
        backgroundScope.launch { coordinator.whileForeground() }
        runCurrent()
        assertEquals(2, relay.updates.size)
        assertFalse(relay.updates.last().second.settings.pushToWatch)
        assertEquals(PushRegistrationStatus.Registered, coordinator.status.value)
        assertTrue(relay.enrollments.isEmpty() && relay.revocations.isEmpty())
    }
}
