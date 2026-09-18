package com.scooter.slackwear.core.model

data class SlackPermalink(
    val conversationId: String,
    val ts: String,
    val threadTs: String? = null,

    val url: String,
)

private val PERMALINK = Regex(
    "https://[A-Za-z0-9-]+\\.slack\\.com/archives/([A-Z0-9]+)/p(\\d{10,})(\\?[^\\s]*)?",
)

fun findSlackPermalinks(text: String): List<SlackPermalink> =
    PERMALINK.findAll(text).mapNotNull { match ->
        val digits = match.groupValues[2]
        if (digits.length <= MICROSECOND_DIGITS) return@mapNotNull null
        val seconds = digits.dropLast(MICROSECOND_DIGITS)
        val micros = digits.takeLast(MICROSECOND_DIGITS)
        SlackPermalink(
            conversationId = match.groupValues[1],
            ts = "$seconds.$micros",

            threadTs = match.groupValues[3]
                .takeIf(String::isNotEmpty)
                ?.removePrefix("?")
                ?.split("&")
                ?.firstOrNull { it.startsWith("thread_ts=") }
                ?.removePrefix("thread_ts="),
            url = match.value,
        )
    }.toList()

private const val MICROSECOND_DIGITS = 6
