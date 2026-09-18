package com.scooter.slackwear.core.auth

import com.scooter.slackwear.core.network.model.ResponseMetadata
import com.scooter.slackwear.core.network.model.SlackEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface InternalAuthApi {

    @FormUrlEncoded
    @POST("auth.findTeam")
    suspend fun findTeam(@Field("domain") domain: String): FindTeamResponse

    @FormUrlEncoded
    @POST("auth.emailToken")
    suspend fun emailToken(
        @Field("user") user: String,
        @Field("email") email: String,
        @Field("team") team: String? = null,
    ): SimpleAuthResponse

    @FormUrlEncoded
    @POST("auth.sso")
    suspend fun sso(
        @Field("team") team: String,
        @Field("redir") redir: String,
        @Field("v2") v2: String = "true",
    ): SsoResponse

    @FormUrlEncoded
    @POST("auth.loginMagic")
    suspend fun loginMagic(
        @Field("team") team: String,
        @Field("magic_token") magicToken: String,
        @Field("tracker") tracker: String? = null,
        @Field("two_factor_pin") twoFactorPin: String? = null,
        @Field("two_factor_native_supported") twoFactorNativeSupported: String? = null,
        @Field("two_factor_is_backup") twoFactorIsBackup: String? = null,
        @Field("approved_device_token") approvedDeviceToken: String? = null,
    ): Response<MagicLoginResponse>

    @FormUrlEncoded
    @POST("auth.loginMagicSSO")
    suspend fun loginMagicSso(
        @Field("team") team: String,
        @Field("magic_token") magicToken: String,
    ): Response<MagicSsoResponse>

    @GET("auth.magicLogins.info")
    suspend fun magicLoginsInfo(
        @Query("magic_token") magicToken: String,
        @Query("team_id") teamId: String? = null,
        @Query("user_id") userId: String? = null,
    ): MagicLoginsInfoResponse

    @FormUrlEncoded
    @POST("auth.signin")
    suspend fun signIn(
        @Field("email") email: String,
        @Field("team") team: String,
        @Field("password") password: String,
        @Field("pin") pin: String? = null,
        @Field("backup") backup: String? = null,
    ): PasswordSignInResponse

    @FormUrlEncoded
    @POST("auth.signout")
    suspend fun signOut(
        @Field("token") token: String,
        @Field("reason") reason: String? = null,
    ): SimpleAuthResponse
}

interface TokenAuthResponse : SlackEnvelope {
    val token: String?
    val userId: String?
    val teamId: String?
    val userEmail: String?
}

@Serializable
data class SimpleAuthResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
) : SlackEnvelope

@Serializable
data class FindTeamResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("team_id") val teamId: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    val sso: Boolean = false,
    @SerialName("sso_required") val ssoRequired: String? = null,
    @SerialName("sso_type") val ssoType: String? = null,
    val url: String? = null,
    @SerialName("is_username_signin_permitted") val isUsernameSigninPermitted: Boolean? = null,
    @SerialName("email_domains") val emailDomains: List<String>? = null,
    @SerialName("email_domains_count") val emailDomainsCount: Long? = null,
) : SlackEnvelope

@Serializable
data class SsoResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val url: String? = null,
    @SerialName("requires_upgrade") val requiresUpgrade: Boolean? = null,
) : SlackEnvelope

@Serializable
data class MagicLoginResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    override val token: String? = null,
    @SerialName("user") override val userId: String? = null,
    @SerialName("team") override val teamId: String? = null,
    @SerialName("user_email") override val userEmail: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("user_name") val userName: String? = null,

    val redir: String? = null,
    val reason: String? = null,
) : TokenAuthResponse

@Serializable
data class MagicSsoResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    override val token: String? = null,
    @SerialName("user") override val userId: String? = null,
    @SerialName("team") override val teamId: String? = null,
    @SerialName("user_email") override val userEmail: String? = null,
) : TokenAuthResponse

@Serializable
data class PasswordSignInResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    override val token: String? = null,
    @SerialName("user") override val userId: String? = null,
    @SerialName("team") override val teamId: String? = null,
    @SerialName("user_email") override val userEmail: String? = null,
) : TokenAuthResponse

@Serializable
data class MagicLoginsInfoResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("team_id") val teamId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("user_email") val userEmail: String? = null,
) : SlackEnvelope
