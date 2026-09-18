package com.scooter.slackwear.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChannelSectionConfig(
    val sections: List<SectionDefinition> = emptyList(),
    val isExplicit: Boolean = false,
) {
    val isConfigured: Boolean get() = isExplicit || sections.isNotEmpty()

    fun sectionFor(channelName: String): String? =
        sections.firstOrNull { channelName in it.channels }?.title

    companion object {

        const val FALLBACK_SECTION = "Channels"
    }
}

@Serializable
data class SectionDefinition(
    val title: String,

    val channels: List<String> = emptyList(),
    val id: String? = null,
    val channelIds: List<String> = emptyList(),
    val isHidden: Boolean = false,
    val isRedacted: Boolean = false,
)
