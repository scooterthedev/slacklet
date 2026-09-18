package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.network.interceptor.RateLimitInterceptor
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RateLimitInterceptorTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client(maxRetries: Int = 2, maxWaitSeconds: Long = 30) = OkHttpClient.Builder()
        .addInterceptor(RateLimitInterceptor(maxRetries, maxWaitSeconds))
        .build()

    private fun rateLimited(retryAfter: String?) = MockResponse.Builder()
        .code(429)
        .apply { retryAfter?.let { headers(okhttp3.Headers.headersOf("Retry-After", it)) } }
        .body("{\"ok\":false,\"error\":\"ratelimited\"}")
        .build()

    private fun ok() = MockResponse.Builder().code(200).body("{\"ok\":true}").build()

    private fun call(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/api/conversations.list")).build()).execute()

    @Test
    fun aSuccessfulCallIsPassedStraightThrough() {
        server.enqueue(ok())
        call(client()).use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRateLimitedCallIsRetriedAndTheSecondAnswerIsReturned() {
        server.enqueue(rateLimited("0"))
        server.enqueue(ok())
        call(client()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retriesAreBoundedSoASustainedRateLimitCannotLoopForever() {
        repeat(4) { server.enqueue(rateLimited("0")) }
        call(client(maxRetries = 2)).use { assertEquals(429, it.code) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aRetryAfterLongerThanWeWillWaitGivesUpWithoutSleeping() {
        server.enqueue(rateLimited("600"))
        val started = System.currentTimeMillis()
        call(client(maxWaitSeconds = 30)).use { assertEquals(429, it.code) }
        assertEquals(1, server.requestCount)
        assertTrue("should not have slept", System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun anUnparseableRetryAfterFallsBackToTheDefaultWaitRatherThanFailing() {
        server.enqueue(rateLimited("not-a-number"))
        server.enqueue(ok())
        call(client()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aMissingRetryAfterHeaderStillRetries() {
        server.enqueue(rateLimited(null))
        server.enqueue(ok())
        call(client()).use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anInterruptWhileWaitingSurfacesAsIoAndKeepsTheInterruptFlag() {
        server.enqueue(rateLimited("20"))
        server.enqueue(ok())
        var raised: Exception? = null
        var flagAfterwards = false
        val worker = Thread {
            try {
                call(client(maxWaitSeconds = 30)).close()
            } catch (error: Exception) {
                raised = error
                flagAfterwards = Thread.currentThread().isInterrupted
            }
        }
        worker.start()
        Thread.sleep(500)
        worker.interrupt()
        worker.join(10_000)

        assertTrue("interceptor never finished", !worker.isAlive)
        assertTrue("expected an IOException, got $raised", raised is IOException)
        assertTrue("the interrupt flag was swallowed", flagAfterwards)
    }
}
