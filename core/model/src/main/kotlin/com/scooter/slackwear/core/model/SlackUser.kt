package com.scooter.slackwear.core.model

data class SlackUser(
    val id: String,
    val displayName: String,
    val realName: String,
    val avatarUrl: String?,
    val isBot: Boolean = false,
) {

    val initials: String
        get() = displayName.ifBlank { realName }
            .split(' ', '.', '-', '_')
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first() }
            .joinToString("")
            .ifEmpty { "?" }
}
