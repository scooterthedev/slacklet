package com.scooter.slackwear.core.model.repository

interface RelaySignInGateway {

    suspend fun start(domain: String): Result<RelaySignInStarted>

    suspend fun poll(code: String): Result<RelaySignInStatus>
}

data class RelaySignInStarted(val code: String)

data class RelaySignInStatus(
    val ready: Boolean,
    val teamId: String? = null,
    val magicToken: String? = null,
    val error: String? = null,
)
