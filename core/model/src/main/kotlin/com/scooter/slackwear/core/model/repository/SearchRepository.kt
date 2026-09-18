package com.scooter.slackwear.core.model.repository

interface SearchRepository {

    suspend fun search(query: String, sort: SearchSort = SearchSort.NEWEST): Result<List<SearchHit>>
}

enum class SearchSort(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    RELEVANCE("Relevant"),
}

enum class SearchHitKind { MESSAGE, CHANNEL, PERSON, FILE }

data class SearchHit(
    val kind: SearchHitKind,

    val id: String = "",

    val conversationId: String = "",

    val conversationName: String = "",

    val authorName: String = "",

    val text: String = "",
)
