package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.network.model.ActivityFetchMode
import com.scooter.slackwear.core.network.model.ActivityFeedResponse
import com.scooter.slackwear.core.network.model.ActivityMarkReadResponse
import com.scooter.slackwear.core.network.model.ActivityViewsResponse
import com.scooter.slackwear.core.network.model.ClientBadgeCountsResponse
import com.scooter.slackwear.core.network.model.ClientChannelsResponse
import com.scooter.slackwear.core.network.model.ClientCountsResponse
import com.scooter.slackwear.core.network.model.ClientCountsSummaryResponse
import com.scooter.slackwear.core.network.model.CountsRequest
import com.scooter.slackwear.core.network.model.HuddleGetResponse
import com.scooter.slackwear.core.network.model.HuddleHistoryResponse
import com.scooter.slackwear.core.network.model.SearchModulesRequest
import com.scooter.slackwear.core.network.model.SearchModulesResponse
import com.scooter.slackwear.core.network.model.SimpleResponse
import com.scooter.slackwear.core.network.model.ThreadsGetRequest
import com.scooter.slackwear.core.network.model.ThreadsGetResponse
import com.scooter.slackwear.core.network.model.TopEmojisResponse
import com.scooter.slackwear.core.network.model.UserBootRequest
import com.scooter.slackwear.core.network.model.UserBootResponse
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface ClientApi {

    @POST("client.userBoot")
    suspend fun userBoot(@Body request: UserBootRequest): UserBootResponse

    @FormUrlEncoded
    @POST("client.channels")
    suspend fun channels(
        @Field("platform") platform: String = "android",
        @Field("num_channels_limit") numChannelsLimit: Long? = null,
        @Field("min_channel_updated") minChannelUpdated: Long? = null,
        @Field("version_all_channels") versionAllChannels: Boolean? = null,
        @Field("return_all_relevant_mpdms") returnAllRelevantMpdms: Boolean? = null,
        @Field("sections_checksum") sectionsChecksum: String? = null,
        @Field("omit_sections") omitSections: Boolean? = null,
    ): ClientChannelsResponse

    @FormUrlEncoded
    @POST("client.dms")
    suspend fun dms(
        @Field("count") count: Long = 10,
        @Field("cursor") cursor: String? = null,
        @Field("include_unified_messages") includeUnifiedMessages: Boolean? = null,
        @Field("only_unreads") onlyUnreads: Boolean? = null,
        @Field("exclude_bots") excludeBots: Boolean? = null,
        @Field("include_closed") includeClosed: Boolean? = null,
    ): JsonObject

    @POST("client.counts")
    suspend fun counts(@Body request: CountsRequest): ClientCountsResponse

    @POST("client.countsSummary")
    suspend fun countsSummary(): ClientCountsSummaryResponse

    @POST("client.badgeCounts")
    suspend fun badgeCounts(): ClientBadgeCountsResponse

    @POST("activity.views")
    suspend fun activityViews(): ActivityViewsResponse

    @FormUrlEncoded
    @POST("activity.feed")
    suspend fun activityFeed(
        @Field("mode") mode: String? = ActivityFetchMode.CHRONO_V1.wireValue,
        @Field("unread_only") unreadOnly: Boolean? = false,
        @Field("limit") limit: Int? = null,
        @Field("is_activity_inbox") isActivityInbox: Boolean? = true,
        @Field("include_badge_counts") includeBadgeCounts: Boolean? = true,
        @Field("types") types: String? = com.scooter.slackwear.core.network.model.defaultActivityTypes,
        @Field("cursor") cursor: String? = null,
        @Field("archive_only") archiveOnly: Boolean? = false,
        @Field("priority_only") priorityOnly: Boolean? = false,
        @Field("channel_ids") channelIds: String? = null,
        @Field("channel_section_ids") channelSectionIds: String? = null,
        @Field("only_salesforce_channels") onlySalesforceChannels: Boolean? = false,
        @Field("automations_only") automationsOnly: Boolean? = false,
        @Field("exclude_automations") excludeAutomations: Boolean? = false,
    ): ActivityFeedResponse

    @POST("activity.markRead")
    suspend fun activityMarkRead(@Body request: JsonObject): ActivityMarkReadResponse

    @POST("activity.markUnread")
    suspend fun activityMarkUnread(@Body request: JsonObject): SimpleResponse

    @FormUrlEncoded
    @POST("activity.badgeCounts")
    suspend fun activityBadgeCounts(
        @Field("include_has_any_unreads") includeHasAnyUnreads: Boolean? = null,
    ): ClientBadgeCountsResponse

    @POST("search.modules")
    suspend fun searchModules(@Body request: SearchModulesRequest): SearchModulesResponse

    @FormUrlEncoded
    @POST("search.autocomplete.topEmojis")
    suspend fun topEmojis(
        @Field("count") count: Double? = null,
        @Field("fetch_all") fetchAll: Boolean? = null,
    ): TopEmojisResponse

    @FormUrlEncoded
    @POST("subscriptions.thread.getView")
    suspend fun threadsGetView(
        @Field("current_ts") currentTs: String? = null,
        @Field("limit") limit: Long? = null,
        @Field("fetch_threads_state") fetchThreadsState: Boolean? = null,
        @Field("priority_mode") priorityMode: String? = null,
        @Field("channel_id") channelId: String? = null,
        @Field("unread_limit") unreadLimit: Long? = null,
    ): JsonObject

    @FormUrlEncoded
    @POST("subscriptions.thread.mark")
    suspend fun threadMark(
        @Field("channel") channel: String,
        @Field("thread_ts") threadTs: String,
        @Field("ts") ts: String,
        @Field("read") read: Boolean = true,
    ): SimpleResponse

    @FormUrlEncoded
    @POST("subscriptions.thread.add")
    suspend fun threadAdd(
        @Field("channel") channel: String,
        @Field("thread_ts") threadTs: String,
        @Field("last_read") lastRead: String? = null,
    ): SimpleResponse

    @FormUrlEncoded
    @POST("subscriptions.thread.remove")
    suspend fun threadRemove(
        @Field("channel") channel: String,
        @Field("thread_ts") threadTs: String,
    ): SimpleResponse

    @FormUrlEncoded
    @POST("huddles.get")
    suspend fun huddleGet(@Field("huddle_id") huddleId: String): HuddleGetResponse

    @POST("huddles.getIndirectHuddle")
    suspend fun huddleGetIndirect(@Body request: JsonObject): JsonObject

    @FormUrlEncoded
    @POST("huddles.knock")
    suspend fun huddleKnock(@Field("channel_id") channelId: String): JsonObject

    @FormUrlEncoded
    @POST("huddles.cancelKnock")
    suspend fun huddleCancelKnock(@Field("channel_id") channelId: String): JsonObject
}
