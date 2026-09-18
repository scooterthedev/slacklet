package com.scooter.slackwear.core.model.repository

import kotlinx.coroutines.flow.Flow

interface HuddleRepository {

    fun observeHuddle(conversationId: String): Flow<HuddleState?>

    suspend fun refresh(conversationId: String): Result<Unit>

    suspend fun knock(conversationId: String): Result<Unit>

    suspend fun cancelKnock(conversationId: String): Result<Unit>
}

data class HuddleState(
    val conversationId: String,
    val huddleId: String,
    val active: Boolean,
    val participantCount: Int,
    val participantNames: List<String> = emptyList(),
)
