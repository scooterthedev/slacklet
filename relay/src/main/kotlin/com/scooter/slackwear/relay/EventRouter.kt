package com.scooter.slackwear.relay

private val logger = org.slf4j.LoggerFactory.getLogger("EventRouter")

class EventRouter(private val devices: DeviceStore, private val now: () -> Long = System::currentTimeMillis) {
    private val unrouted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun plan(envelope: SlackEventEnvelope): List<Delivery> {
        val teamId = envelope.teamId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val eventId = envelope.eventId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val event = envelope.event ?: return emptyList()
        if (envelope.type != "event_callback" || event.type != "message" ||
            event.subtype != null || event.botId != null || event.channel.isNullOrBlank() ||
            event.ts.isNullOrBlank() || event.user.isNullOrBlank()
        ) return emptyList()

        val scopes = (listOf(teamId) + listOfNotNull(envelope.enterpriseId)).toSet()
        return envelope.authorizations.asSequence()
            .filter { it.teamId == teamId && it.isBot == false }
            .mapNotNull { authorization ->
                authorization.userId?.takeIf(String::isNotBlank)
                    ?.let { it to scopes + listOfNotNull(authorization.enterpriseId) }
            }
            .flatMap { (userId, candidates) -> devices.forAnyTeam(candidates, userId).asSequence() }
            .distinct()
            .toList()
            .also { if (it.isEmpty()) noteUnrouted(envelope) }
            .asSequence()
            .filter { it.slackUserId != event.user }
            .mapNotNull { device ->
                val kind = classify(event, device.slackUserId) ?: return@mapNotNull null
                if (!device.settings.allows(kind)) return@mapNotNull null
                Delivery(
                    key = DeliveryKey(eventId, device.teamId, device.slackUserId, device.tokenGeneration),
                    payload = buildMap {
                        put("v", "2")
                        put("eventId", eventId)
                        put("registrationId", device.registrationId)
                        put("teamId", device.teamId)
                        put("slackUserId", device.slackUserId)
                        put("expiresAt", (now() + 3_600_000).toString())
                        put("kind", kind.name)
                        put("channelId", event.channel)
                        put("ts", event.ts)
                        put("authorId", event.user)
                        event.threadTs?.let { put("threadTs", it) }
                        put("preview", event.text.orEmpty().take(120))
                    },
                )
            }.toList()
    }

    private fun noteUnrouted(envelope: SlackEventEnvelope) {
        val key = envelope.teamId.orEmpty() + "/" + envelope.enterpriseId.orEmpty()
        if (unrouted.size < 32 && unrouted.add(key)) {
            logger.info(
                "Unrouted: envelope team={} enterprise={} authorizations={} | enrolled teams={}",
                envelope.teamId, envelope.enterpriseId,
                envelope.authorizations.map { listOf(it.userId, it.teamId, it.enterpriseId, it.isBot) },
                devices.all().flatMap { it.teamIds + it.teamId }.distinct(),
            )
        }
    }

    private fun classify(event: SlackEvent, userId: String): PushKind? {
        val text = event.text.orEmpty()
        return when {
            "<@$userId>" in text -> PushKind.MENTION
            event.channelType == "im" || event.channelType == "mpim" -> PushKind.DIRECT_MESSAGE
            event.threadTs != null -> null
            else -> PushKind.CHANNEL_ACTIVITY
        }
    }
}

internal fun NotificationSettings.allows(kind: PushKind): Boolean {
    if (!pushToWatch) return false
    return when (kind) {
        PushKind.MENTION -> mentions
        PushKind.DIRECT_MESSAGE -> directMessages
        PushKind.THREAD_REPLY -> threadReplies
        PushKind.CHANNEL_ACTIVITY -> allActivity
    }
}
