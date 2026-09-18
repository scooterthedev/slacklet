package com.scooter.slackwear.relay

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

internal fun Route.notificationEnrollmentRoutes(devices: DeviceStore, identities: IdentityVerifier) {
    val throttle = EnrollmentThrottle()
    post("/v2/devices/enroll") {
        if (!throttle.allow()) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }
        val request = call.enrollmentBody<EnrollmentRequest>()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, "Enrollment unavailable")
            return@post
        }
        val identity = identities.verify(request.slackToken)
        if (identity == null) {
            call.respond(HttpStatusCode.Unauthorized, "Slack did not recognise that session")
            return@post
        }
        val result = devices.enroll(request, identity)
        if (result == null) call.respond(HttpStatusCode.BadRequest, "Enrollment unavailable")
        else call.respond(result)
    }
    put("/v2/devices/{registrationId}") {
        if (!throttle.allow()) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@put
        }
        val request = call.enrollmentBody<DeviceUpdateRequest>()
        val updated = request != null && devices.update(call.parameters["registrationId"].orEmpty(), call.capability(), request)
        call.respond(if (updated) HttpStatusCode.NoContent else HttpStatusCode.Unauthorized)
    }
    delete("/v2/devices/{registrationId}") {
        if (!throttle.allow()) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@delete
        }
        val revoked = devices.revoke(call.parameters["registrationId"].orEmpty(), call.capability())
        call.respond(if (revoked) HttpStatusCode.NoContent else HttpStatusCode.Unauthorized)
    }
}

private fun ApplicationCall.capability(): String = request.headers["Authorization"]
    ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()

private suspend inline fun <reified T> ApplicationCall.enrollmentBody(): T? = try {
    withTimeout(5_000) {
        val input = receiveChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.readAvailable(buffer, 0, buffer.size)
            if (count == -1) break
            if (output.size() + count > 16_384) return@withTimeout null
            output.write(buffer, 0, count)
        }
        Json.decodeFromString<T>(output.toString(Charsets.UTF_8))
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal class EnrollmentThrottle(private val now: () -> Long = System::nanoTime) {
    private var start = now()
    private var count = 0

    @Synchronized
    fun allow(): Boolean {
        val current = now()
        if (current - start >= 60_000_000_000) {
            start = current
            count = 0
        }
        if (count >= 60) return false
        count++
        return true
    }
}
