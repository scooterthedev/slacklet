package com.scooter.slackwear.core.model

data class Conversation(
    val id: String,
    val name: String,
    val kind: ConversationKind,
    val topic: String? = null,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,

    val counterpartUserId: String? = null,

    val isOpen: Boolean = false,
) {

    val displayName: String
        get() = when (kind) {
            ConversationKind.PUBLIC_CHANNEL, ConversationKind.PRIVATE_CHANNEL -> "#$name"
            ConversationKind.GROUP_MESSAGE -> groupMembers?.let(::summariseMembers) ?: name
            ConversationKind.DIRECT_MESSAGE -> name
        }

    val groupMembers: List<String>?
        get() {
            if (kind != ConversationKind.GROUP_MESSAGE || !name.startsWith(MPDM_PREFIX)) return null
            return name.removePrefix(MPDM_PREFIX)
                .removeSuffix(MPDM_SUFFIX)
                .split(MPDM_SEPARATOR)
                .filter(String::isNotBlank)
                .takeIf { it.isNotEmpty() }
        }
}

private fun summariseMembers(members: List<String>): String = when {
    members.size <= MAX_NAMED_MEMBERS -> members.joinToString(", ")
    else -> members.take(MAX_NAMED_MEMBERS).joinToString(", ") + " +${members.size - MAX_NAMED_MEMBERS}"
}

private const val MPDM_PREFIX = "mpdm-"
private const val MPDM_SUFFIX = "-1"
private const val MPDM_SEPARATOR = "--"
private const val MAX_NAMED_MEMBERS = 3

enum class ConversationKind {
    PUBLIC_CHANNEL,
    PRIVATE_CHANNEL,
    DIRECT_MESSAGE,
    GROUP_MESSAGE,
}
