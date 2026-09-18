package com.scooter.slackwear.core.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class SlackAuthInterceptor(
    private val tokenProvider: () -> String?,
    private val deviceTokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val bearer = tokenProvider()
        val device = deviceTokenProvider()

        val builder = chain.request().newBuilder()
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (device != null) builder.header("Cookie", "d=$device")

        return chain.proceed(builder.build())
    }
}
