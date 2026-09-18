package com.scooter.slackwear.core.network.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UserBootRequest(
    @EncodeDefault val platform: String = "android",
    @SerialName("compress_workspace_prefs") val compressWorkspacePrefs: Boolean? = null,
    @SerialName("num_channels_limit") val numChannelsLimit: Long? = null,
    @SerialName("min_channel_updated") val minChannelUpdated: Long? = null,
    @SerialName("prefs_version") val prefsVersion: String? = null,
    @SerialName("omit_restricted_channels") val omitRestrictedChannels: Boolean? = null,
    @SerialName("mobile_reload_ts") val mobileReloadTs: Long? = null,
    @SerialName("version_all_channels") val versionAllChannels: Boolean? = null,
    @SerialName("return_all_relevant_mpdms") val returnAllRelevantMpdms: Boolean? = null,
    @SerialName("omit_extras") val omitExtras: List<String>? = null,
)

@Serializable
data class CountsRequest(
    @SerialName("org_wide_aware") val orgWideAware: Boolean? = null,
    @SerialName("thread_counts_by_channel") val threadCountsByChannel: Boolean? = null,
    @SerialName("include_file_channels") val includeFileChannels: Boolean? = null,
    @SerialName("dry_run_last_fetched") val dryRunLastFetched: Long? = null,
    @SerialName("counts_last_fetched") val countsLastFetched: Long? = null,
    @SerialName("channel_ids") val channelIds: List<String>? = null,
    @SerialName("include_all_unreads") val includeAllUnreads: Boolean? = null,
)

@Serializable
data class SearchModulesRequest(
    val module: String? = null,
    val query: String? = null,
    val count: Long? = null,
    val cursor: String? = null,
    val page: Long? = null,
    @SerialName("min_ts") val minTs: Long? = null,
    @SerialName("no_user_profile") val noUserProfile: Boolean? = null,
    val highlight: Boolean? = null,
    val tz: String? = null,
    @SerialName("search_exclude_bots") val searchExcludeBots: Boolean? = null,
    @SerialName("search_exclude_me") val searchExcludeMe: Boolean? = null,
    @SerialName("extra_message_data") val extraMessageData: Boolean? = null,
    val sort: String? = null,
    @SerialName("sort_dir") val sortDirection: String? = null,
)

@Serializable
data class ThreadsGetRequest(
    val channel: String? = null,
    @SerialName("thread_ts") val threadTs: String? = null,
    val threads: List<ThreadsRef>? = null,
)

val defaultActivityTypes: String = kotlinx.serialization.json.JsonArray(
    listOf(
        "at_user", "at_user_group", "at_channel", "at_everyone", "bot_dm_bundle",
        "external_channel_invite", "external_dm_invite", "internal_channel_invite",
        "quietly_added_to_channel", "keyword", "message_reaction", "list_record_edited",
        "list_user_mentioned", "list_record_assigned", "thread_v2", "saved_reminder",
        "list_todo_notification", "list_approval_request", "list_approval_reviewed", "dm",
        "unjoined_channel_mention", "prejoin_dm_welcome_party_alert",
    ).map { kotlinx.serialization.json.JsonPrimitive(it) },
).toString()

enum class ActivityFetchMode(val wireValue: String) {
    CHRONO_READS_AND_UNREADS("chrono_reads_and_unreads"),
    CHRONO_UNREADS("chrono_unreads"),
    CHRONO_PRIORITY_ONLY_READS_AND_UNREADS_V1("chrono_priority_only_reads_and_unreads_v1"),
    CHRONO_PRIORITY_ONLY_UNREADS_V1("chrono_priority_only_unreads_v1"),
    CHRONO_THREADS_UNREADS_V2("chrono_threads_unreads_v2"),
    CHRONO_THREADS_READS_AND_UNREADS_V2("chrono_threads_reads_and_unreads_v2"),
    PRIORITY_READS_AND_UNREADS_V1("priority_reads_and_unreads_v1"),
    PRIORITY_UNREADS_V1("priority_unreads_v1"),
    CHRONO_V1("chrono_v1"),
    UNREADS_FIRST_V1("unreads_first_v1"),
    PRIORITY_UNREADS_FIRST_V1("priority_unreads_first_v1"),
}

@Serializable
data class ThreadsRef(
    @SerialName("channel_id") val channelId: String,
    @SerialName("thread_ts") val threadTs: String,
)

@Serializable
data class ClientCountsChannel(
    val id: String = "",
    @SerialName("has_unreads") val hasUnreads: Boolean? = null,
    @SerialName("mention_count") val mentionCount: Long? = null,
    @SerialName("vip_count") val vipCount: Long? = null,
    @SerialName("last_read") val lastRead: String? = null,
    @SerialName("latest") val latest: String? = null,
    @SerialName("updated") val updated: String? = null,
    @SerialName("history_invalid") val historyInvalid: String? = null,
)

@Serializable
data class ClientCountsThreads(
    @SerialName("has_unreads") val hasUnreads: Boolean = false,
    @SerialName("mention_count") val mentionCount: Long = 0,
    @SerialName("vip_count") val vipCount: Long? = null,
    @SerialName("mention_count_by_channel") val mentionCountByChannel: Map<String, Long> = emptyMap(),
    @SerialName("unread_count_by_channel") val unreadCountByChannel: Map<String, Long> = emptyMap(),
)

@Serializable
data class ClientCountsResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val channels: List<ClientCountsChannel> = emptyList(),
    val ims: List<ClientCountsChannel> = emptyList(),
    val mpims: List<ClientCountsChannel> = emptyList(),
    val threads: ClientCountsThreads? = null,
    @SerialName("channel_badges") val channelBadges: JsonObject? = null,
    @SerialName("counts_last_fetched") val countsLastFetched: Long? = null,
) : SlackEnvelope

@Serializable
data class ClientCountsSummaryResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("has_unreads") val hasUnreads: Boolean = false,
    @SerialName("channel_badges") val channelBadges: ClientCountsBadges? = null,
) : SlackEnvelope

@Serializable
data class ClientCountsBadges(
    val channels: Long = 0,
    val dms: Long = 0,
    @SerialName("app_dms") val appDms: Long = 0,
    @SerialName("thread_mentions") val threadMentions: Long = 0,
    @SerialName("thread_unreads") val threadUnreads: Long = 0,
)

@Serializable
data class ClientBadgeCountsResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("has_any_unreads") val hasAnyUnreads: Boolean = false,
    @SerialName("activity_unread_count_by_entry_type")
    val activityUnreadCountByEntryType: Map<String, Long> = emptyMap(),
    @SerialName("later_overdue_count") val laterOverdueCount: Long? = null,
) : SlackEnvelope

@Serializable
data class UserBootResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val self: JsonObject? = null,
    val team: JsonObject? = null,
    val channels: List<JsonObject> = emptyList(),
    val ims: List<JsonObject> = emptyList(),
    val mpims: List<JsonObject> = emptyList(),
    @SerialName("default_workspace") val defaultWorkspace: String? = null,
    @SerialName("prefs_version") val prefsVersion: String? = null,
    @SerialName("accept_tos_url") val acceptTosUrl: String? = null,
    @SerialName("should_reauth") val shouldReauth: Boolean? = null,
    @SerialName("updated_token") val updatedToken: String? = null,
    @SerialName("is_open") val isOpen: List<String>? = null,
    val starred: List<String> = emptyList(),
    val prefs: JsonObject? = null,
) : SlackEnvelope

@Serializable
data class ClientChannelsResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val channels: ClientChannelsBody? = null,
    val sections: ClientSections? = null,
) : SlackEnvelope

@Serializable
data class ClientChannelsBody(
    val channels: List<JsonObject> = emptyList(),
    val ims: List<JsonObject> = emptyList(),
    val mpims: List<JsonObject> = emptyList(),
    @SerialName("is_open") val isOpen: List<String>? = null,
    @SerialName("channels_priority") val channelsPriority: Map<String, Double> = emptyMap(),
    @SerialName("has_more_mpdms") val hasMoreMpdms: Boolean = false,
)

@Serializable
data class ClientSections(
    @SerialName("channel_sections") val channelSections: List<JsonObject> = emptyList(),
)

@Serializable
data class SearchModulesResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val module: String? = null,
    val query: String? = null,
    val filters: JsonElement? = null,
    val items: JsonElement? = null,
    @SerialName("mixed_results") val mixedResults: List<SearchMixedResult> = emptyList(),
    val pagination: SearchPagination? = null,
) : SlackEnvelope

@Serializable
data class SearchMixedResult(
    @SerialName("module_type") val moduleType: String? = null,
    val results: List<JsonElement> = emptyList(),
)

@Serializable
data class SearchPagination(
    @SerialName("next_cursor") val nextCursor: String? = null,
    val page: Long? = null,
    @SerialName("page_count") val pageCount: Long? = null,
    val first: Long? = null,
    val last: Long? = null,
)

@Serializable
data class TopEmojisResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,

    val org: List<String> = emptyList(),

    val user: List<String> = emptyList(),
) : SlackEnvelope

@Serializable
data class ActivityViewsResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val views: List<JsonObject> = emptyList(),
    val prefs: JsonObject? = null,
) : SlackEnvelope

@Serializable
data class ActivityFeedResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val items: List<JsonObject> = emptyList(),
) : SlackEnvelope

@Serializable
data class ThreadsGetResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val subscriptions: List<String> = emptyList(),
    @SerialName("subscriptions_by_channel") val subscriptionsByChannel: Map<String, List<String>> = emptyMap(),
) : SlackEnvelope

@Serializable
data class HuddleGetResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val huddle: JsonObject? = null,
) : SlackEnvelope

@Serializable
data class HuddleHistoryResponse(
    override val ok: Boolean = true,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    val huddles: List<JsonObject> = emptyList(),
) : SlackEnvelope
