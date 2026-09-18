package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.model.SafeFailure
import com.scooter.slackwear.core.model.SlackFailureCode
import com.scooter.slackwear.core.model.toSafeFailure
import com.scooter.slackwear.core.network.model.SlackEnvelope
import retrofit2.HttpException

class SlackApiException(val code: String) : Exception("Slack API error: $code"), SlackFailureCode {
    override val slackFailureCode: String get() = code

    private companion object {
        val REAUTH_CODES = setOf(
            "invalid_auth",
            "not_authed",
            "token_revoked",
            "token_expired",
            "account_inactive",
        )
    }
}

fun <T : SlackEnvelope> T.unwrap(): T =
    if (ok) this else throw SlackApiException(error ?: "unknown_error")

fun Throwable.toNetworkFailure(): SafeFailure = when (this) {
    is HttpException -> SafeFailure.http(code())
    else -> toSafeFailure()
}
