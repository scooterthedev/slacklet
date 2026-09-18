package com.scooter.slackwear.core.model

data class PaginationState(
    val initialized: Boolean = false,
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
    val error: SafeFailure? = null,
    val pagesLoaded: Int = 0,
    val initialPageTimestamps: Set<String>? = null,
) {
    val canLoadNext: Boolean get() = initialized && !isLoading && !endReached && error == null
}
