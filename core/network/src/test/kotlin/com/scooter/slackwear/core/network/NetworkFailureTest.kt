package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.model.SafeFailure
import com.scooter.slackwear.core.model.toSafeFailure
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class NetworkFailureTest {
    @Test
    fun slackExceptionImplementsSharedContract() {
        assertEquals("not_allowed", SlackApiException("not_allowed").toSafeFailure().code)
        assertNull(SlackApiException("xoxb-private U123 query").toSafeFailure().code)
    }

    @Test
    fun httpMapperRetainsOnlyStatus() {
        val error = HttpException(Response.error<Unit>(403, "private response query token U123".toResponseBody()))
        val safe = error.toNetworkFailure()
        assertEquals(SafeFailure.Category.HTTP, safe.category)
        assertEquals(403, safe.httpStatus)
        assertEquals("HTTP 403", safe.reason)
    }
}
