package com.scooter.slackwear.core.model.repository

import com.scooter.slackwear.core.model.ChannelSectionConfig

interface SidebarRepository {

    suspend fun refresh(): Result<Unit>

    suspend fun sections(): Result<ChannelSectionConfig>
}
