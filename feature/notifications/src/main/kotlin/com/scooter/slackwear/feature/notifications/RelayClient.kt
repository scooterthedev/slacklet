package com.scooter.slackwear.feature.notifications

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface RelayApi {
    @POST("v2/devices/enroll")
    suspend fun enroll(@Body request: EnrollmentRequest): EnrollmentResponse

    @PUT("v2/devices/{registrationId}")
    suspend fun update(@Path("registrationId") registrationId: String, @Header("Authorization") authorization: String, @Body request: DeviceUpdateRequest)

    @DELETE("v2/devices/{registrationId}")
    suspend fun revoke(@Path("registrationId") registrationId: String, @Header("Authorization") authorization: String)

    @POST("auth/start")
    suspend fun startAuth(@Body request: AuthStartRequest): AuthStartResponse

    @GET("auth/{code}/status")
    suspend fun authStatus(@Path("code") code: String): AuthStatusResponse
}

@Serializable
class EnrollmentRequest(
    val teamId: String,
    val deviceId: String,
    val capabilityHash: String,
    val fcmToken: String,
    val slackToken: String,
    val settings: RelayNotificationSettings,
)

@Serializable
data class EnrollmentResponse(val registrationId: String, val teamId: String, val userId: String)

@Serializable
data class DeviceUpdateRequest(val fcmToken: String, val settings: RelayNotificationSettings)

@Serializable
data class RelayNotificationSettings(
    val pushToWatch: Boolean = true,
    val mentions: Boolean = true,
    val directMessages: Boolean = true,
    val threadReplies: Boolean = true,
    val allActivity: Boolean = false,
    val followSlackDnd: Boolean = true,
)

@Serializable
data class AuthStartRequest(val domain: String)

@Serializable
data class AuthStartResponse(val code: String)

@Serializable
data class AuthStatusResponse(
    val ready: Boolean,
    val teamId: String? = null,
    val magicToken: String? = null,
    val error: String? = null,
)
