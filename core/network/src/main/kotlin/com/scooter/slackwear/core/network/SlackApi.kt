package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.network.model.AuthTestResponse
import com.scooter.slackwear.core.network.model.ConversationHistoryResponse
import com.scooter.slackwear.core.network.model.ConversationInfoResponse
import com.scooter.slackwear.core.network.model.ConversationsListResponse
import com.scooter.slackwear.core.network.model.DndInfoResponse
import com.scooter.slackwear.core.network.model.EmojiListResponse
import com.scooter.slackwear.core.network.model.PostMessageResponse
import com.scooter.slackwear.core.network.model.SimpleResponse
import com.scooter.slackwear.core.network.model.UploadUrlResponse
import com.scooter.slackwear.core.network.model.UsersInfoResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface SlackApi {

    @GET("users.channelSections.list")
    suspend fun channelSections(): JsonObject

    @GET("conversations.info")
    suspend fun conversationInfo(
        @Query("channel") channel: String,
    ): ConversationInfoResponse

    @GET("conversations.history")
    suspend fun conversationHistory(
        @Query("channel") channel: String,
        @Query("limit") limit: Int = 30,
        @Query("cursor") cursor: String? = null,
        @Query("oldest") oldest: String? = null,

        @Query("latest") latest: String? = null,
        @Query("inclusive") inclusive: Boolean? = null,
    ): ConversationHistoryResponse

    @GET("conversations.replies")
    suspend fun conversationReplies(
        @Query("channel") channel: String,
        @Query("ts") ts: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): ConversationHistoryResponse

    @GET("emoji.list")
    suspend fun emojiList(): EmojiListResponse

    @GET("files.getUploadURLExternal")
    suspend fun getUploadUrl(
        @Query("filename") filename: String,
        @Query("length") length: Long,
    ): UploadUrlResponse

    @FormUrlEncoded
    @POST("files.completeUploadExternal")
    suspend fun completeUpload(
        @Field("files") filesJson: String,
        @Field("channel_id") channelId: String,
        @Field("thread_ts") threadTs: String? = null,
        @Field("initial_comment") initialComment: String? = null,
    ): SimpleResponse

    @GET("dnd.info")
    suspend fun dndInfo(): DndInfoResponse

    @FormUrlEncoded
    @POST("dnd.setSnooze")
    suspend fun setSnooze(@Field("num_minutes") minutes: Int): SimpleResponse

    @POST("dnd.endSnooze")
    suspend fun endSnooze(): SimpleResponse

    @GET("users.info")
    suspend fun userInfo(
        @Query("user") user: String,
    ): UsersInfoResponse

    @FormUrlEncoded
    @POST("conversations.mark")
    suspend fun markConversation(
        @Field("channel") channel: String,
        @Field("ts") ts: String,
    ): SimpleResponse

    @FormUrlEncoded
    @POST("chat.postMessage")
    suspend fun postMessage(
        @Field("channel") channel: String,
        @Field("text") text: String,
        @Field("thread_ts") threadTs: String? = null,
    ): PostMessageResponse

    @FormUrlEncoded
    @POST("reactions.add")
    suspend fun addReaction(
        @Field("channel") channel: String,
        @Field("timestamp") timestamp: String,
        @Field("name") name: String,
    ): SimpleResponse

    @FormUrlEncoded
    @POST("reactions.remove")
    suspend fun removeReaction(
        @Field("channel") channel: String,
        @Field("timestamp") timestamp: String,
        @Field("name") name: String,
    ): SimpleResponse
}
