package com.scooter.slackwear.core.model.repository

import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun observeFeed(): Flow<List<ActivityItem>>
    fun observePageState(): Flow<FeedPageState> = kotlinx.coroutines.flow.flowOf(FeedPageState())
    suspend fun refresh(): Result<Unit>
    suspend fun loadMore(): Result<Unit>
    suspend fun views(): Result<List<ActivityViewFilter>>
    suspend fun badgeCounts(): Result<BadgeCounts>
    suspend fun markRead(request: ActivityMarkReadRequest): Result<Long?>
    suspend fun markUnread(entries: List<ActivityEntryInput>): Result<Unit>
}

data class FeedPageState(
    val initialized: Boolean = false,
    val isLoading: Boolean = false,
    val hasMore: Boolean = false,
    val error: com.scooter.slackwear.core.model.SafeFailure? = null,
)

data class ActivityViewFilter(
    val key: String,
    val title: String,
    val filters: ActivityFilters = ActivityFilters(),
    val sort: String? = null,
    val density: String? = null,
)

data class ActivityFilters(
    val entryTypes: List<String>? = null,
    val channelIds: List<String>? = null,
    val channelSectionIds: List<String>? = null,
    val unreadOnly: Boolean? = null,
    val archiveOnly: Boolean? = null,
    val priorityOnly: Boolean? = null,
    val readState: String? = null,
    val onlySalesforceChannels: Boolean? = null,
    val automationsOnly: Boolean? = null,
    val excludeAutomations: Boolean? = null,
)

data class ActivityEntryInput(
    val type: String,
    val key: String,
    val feedTs: String,
)

data class ActivityMarkReadRequest(
    val type: String? = null,
    val ts: String? = null,
    val threadTs: String? = null,
    val channel: String? = null,
    val key: String? = null,
    val feedTs: String? = null,
    val entries: List<ActivityEntryInput>? = null,
    val undoKey: Long? = null,
)

interface ActivityMutationGateway {
    suspend fun markRead(request: ActivityMarkReadRequest): Long?
    suspend fun markUnread(entries: List<ActivityEntryInput>)
}

fun ActivityItem.entryInput(): ActivityEntryInput? = entryKey?.let {
    ActivityEntryInput(entryType, it, feedTs)
}?.takeIf { it.type.isNotBlank() && it.feedTs.isNotBlank() }
