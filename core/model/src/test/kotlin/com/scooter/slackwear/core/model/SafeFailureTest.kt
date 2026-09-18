package com.scooter.slackwear.core.model

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SafeFailureTest {
    @Test
    fun onlyBoundedLowercaseSlackCodesSurvive() {
        assertEquals("missing_scope", slack("missing_scope").toSafeFailure().code)
        assertEquals("a".repeat(64), slack("a".repeat(64)).toSafeFailure().code)
        listOf("", "a".repeat(65), "U123", "xoxb-secret", "query words", "error\nsecret", "{\"error\":true}").forEach {
            val failure = slack(it).toSafeFailure()
            assertEquals(SafeFailure.Category.SLACK, failure.category)
            assertNull(failure.code)
            assertEquals("Slack error", failure.reason)
        }
    }

    @Test
    fun categoriesNeverReadMessagesOrCauses() {
        val secret = "query=private token=xoxb-synthetic user=U123"
        assertEquals("Connection error", IOException(secret).toSafeFailure().reason)
        assertEquals("Response format error", SerializationException(secret).toSafeFailure().reason)
        assertEquals("Unknown error", IllegalStateException("ratelimited $secret", slack("missing_scope")).toSafeFailure().reason)
        val hostile = object : Exception() {
            override val message: String get() = error("Message must not be read")
        }
        assertEquals(SafeFailure.Category.UNKNOWN, hostile.toSafeFailure().category)
    }

    @Test
    fun httpStatusIsBounded() {
        assertEquals("HTTP 429", SafeFailure.http(429).reason)
        listOf(-1, 0, 99, 600, Int.MAX_VALUE).forEach { assertNull(SafeFailure.http(it).httpStatus) }
    }

    @Test
    fun cancellationIsNotAFailure() {
        val cancellation = CancellationException("synthetic")
        try {
            cancellation.toSafeFailure()
            throw AssertionError("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    private fun slack(code: String) = object : Exception("private"), SlackFailureCode {
        override val slackFailureCode = code
    }
}
