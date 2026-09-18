package com.scooter.slackwear.core.model

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerializationException

interface SlackFailureCode {
    val slackFailureCode: String
}

class SafeFailure private constructor(
    val category: Category,
    val code: String? = null,
    val httpStatus: Int? = null,
) {
    enum class Category { SLACK, HTTP, IO, SERIALIZATION, UNKNOWN }

    val reason: String
        get() = when (category) {
            Category.SLACK -> code?.let { "Slack: $it" } ?: "Slack error"
            Category.HTTP -> httpStatus?.let { "HTTP $it" } ?: "HTTP error"
            Category.IO -> "Connection error"
            Category.SERIALIZATION -> "Response format error"
            Category.UNKNOWN -> "Unknown error"
        }

    companion object {
        fun slack(code: String): SafeFailure = SafeFailure(
            Category.SLACK,
            code = code.takeIf { it.length in 1..64 && it.all { char -> char in 'a'..'z' || char == '_' } },
        )

        fun http(status: Int): SafeFailure = SafeFailure(Category.HTTP, httpStatus = status.takeIf { it in 100..599 })
        fun category(category: Category): SafeFailure = SafeFailure(category)
    }
}

fun Throwable.toSafeFailure(): SafeFailure = when (this) {
    is CancellationException -> throw this
    is SlackFailureCode -> SafeFailure.slack(slackFailureCode)
    is SerializationException -> SafeFailure.category(SafeFailure.Category.SERIALIZATION)
    is IOException -> SafeFailure.category(SafeFailure.Category.IO)
    else -> SafeFailure.category(SafeFailure.Category.UNKNOWN)
}
