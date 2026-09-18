package com.scooter.slackwear.feature.home

import com.scooter.slackwear.core.model.ChannelSectionConfig
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.repository.ConversationSummary

data class SectionedConversations(
    val title: String,
    val conversations: List<ConversationSummary>,
)

private const val SECTION_MENTIONS = "Mentions"
private const val SECTION_DIRECT_MESSAGES = "Direct messages"

fun List<ConversationSummary>.sectionsOf(
    config: ChannelSectionConfig = ChannelSectionConfig(),
): List<SectionedConversations> {
    val byMostRecent = compareByDescending<ConversationSummary> {
        it.unread.lastActivityTs.orEmpty()
    }

    val grouped = groupBy { summary -> summary.sectionTitle(config) }

    val order = buildList {
        add(SECTION_MENTIONS)
        add(SECTION_DIRECT_MESSAGES)
        if (config.isConfigured) {
            addAll(config.sections.map { it.title })
            add(ChannelSectionConfig.FALLBACK_SECTION)
        } else {
            add(ChannelSectionConfig.FALLBACK_SECTION)
        }
    }

    return order.distinct().mapNotNull { title ->
        grouped[title]
            ?.sortedWith(byMostRecent)
            ?.takeIf(List<ConversationSummary>::isNotEmpty)
            ?.let { SectionedConversations(title, it) }
    }
}

private fun ConversationSummary.sectionTitle(config: ChannelSectionConfig): String = when {
    unread.hasMention -> SECTION_MENTIONS

    conversation.kind == ConversationKind.DIRECT_MESSAGE ||
        conversation.kind == ConversationKind.GROUP_MESSAGE -> SECTION_DIRECT_MESSAGES

    else -> config.sectionFor(conversation.name) ?: ChannelSectionConfig.FALLBACK_SECTION
}

fun List<ConversationSummary>.unreadForChannelList(): List<ConversationSummary> =
    filter { it.unread.count > 0 || it.unread.hasMention }
