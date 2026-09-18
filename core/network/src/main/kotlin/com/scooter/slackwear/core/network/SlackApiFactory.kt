package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.network.interceptor.RateLimitInterceptor
import com.scooter.slackwear.core.network.interceptor.SlackAuthInterceptor
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object SlackApiFactory {

    private const val BASE_URL = "https://slack.com/api/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun okHttpClient(tokenProvider: () -> String?, deviceTokenProvider: () -> String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(SlackAuthInterceptor(tokenProvider, deviceTokenProvider))
            .addInterceptor(RateLimitInterceptor())
            .apply {
                if (BuildConfig.LOG_HTTP_BODIES) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {

                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }

            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    fun create(callFactory: Call.Factory): SlackApi =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .callFactory(callFactory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SlackApi::class.java)

    fun createClientApi(callFactory: Call.Factory): ClientApi =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .callFactory(callFactory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClientApi::class.java)
}
