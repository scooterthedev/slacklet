package com.scooter.slackwear.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RateLimitInterceptor(
    private val maxRetries: Int = 2,
    private val maxWaitSeconds: Long = 30,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())

        while (response.code == HTTP_TOO_MANY_REQUESTS && attempt < maxRetries) {
            val waitSeconds = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_WAIT_SECONDS
            if (waitSeconds > maxWaitSeconds) return response

            response.close()
            try {
                Thread.sleep(waitSeconds * 1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting out a Slack rate limit", e)
            }
            attempt++
            response = chain.proceed(chain.request())
        }
        return response
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DEFAULT_WAIT_SECONDS = 1L
    }
}
