package com.scooter.slackwear.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ActivityMarkReadResponse(
    override val ok: Boolean,
    override val error: String? = null,
    @SerialName("response_metadata") override val responseMetadata: ResponseMetadata? = null,
    @SerialName("undo_key") val undoKey: Long? = null,
) : SlackEnvelope
