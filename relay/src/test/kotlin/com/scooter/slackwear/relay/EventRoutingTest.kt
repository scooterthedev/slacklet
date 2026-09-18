package com.scooter.slackwear.relay

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EventRoutingTest {

    private val me = "U_ME"
    private val them = "U_THEM"

    @Test
    fun `a mention is delivered`() = runTest {
        val sent = route(text = "hey <@$me> can you look")
        assertEquals(1, sent.size)
        assertEquals(PushKind.MENTION.name, sent.single()["kind"])
    }

    @Test
    fun `a direct message is delivered`() = runTest {
        val sent = route(text = "you around?", channelType = "im")
        assertEquals(PushKind.DIRECT_MESSAGE.name, sent.single()["kind"])
    }

    @Test
    fun `ordinary channel traffic is suppressed by default`() = runTest {
        assertTrue(route(text = "unrelated chatter").isEmpty())
    }

    @Test
    fun `ordinary channel traffic is delivered when all activity is on`() = runTest {
        val sent = route(
            text = "unrelated chatter",
            settings = NotificationSettings(allActivity = true),
        )
        assertEquals(PushKind.CHANNEL_ACTIVITY.name, sent.single()["kind"])
    }

    @Test
    fun `a user is never notified about their own message`() = runTest {
        assertTrue(route(text = "note to self <@$me>", author = me).isEmpty())
    }

    @Test
    fun `bot messages are suppressed`() = runTest {
        assertTrue(route(text = "<@$me> deploy finished", botId = "B123").isEmpty())
    }

    @Test
    fun `edits and joins are suppressed`() = runTest {
        assertTrue(route(text = "<@$me> hello", subtype = "message_changed").isEmpty())
    }

    @Test
    fun `nothing is delivered while snoozed`() = runTest {
        assertTrue(route(text = "<@$me> urgent", snoozed = true).isEmpty())
    }

    @Test
    fun `snooze is ignored when the user has turned that off`() = runTest {
        val sent = route(
            text = "<@$me> urgent",
            snoozed = true,
            settings = NotificationSettings(followSlackDnd = false),
        )
        assertEquals(1, sent.size)
    }

    @Test
    fun `push disabled suppresses everything`() = runTest {
        val sent = route(
            text = "<@$me> urgent",
            settings = NotificationSettings(pushToWatch = false),
        )
        assertTrue(sent.isEmpty())
    }

    private suspend fun route(
        text: String,
        author: String = them,
        channelType: String = "channel",
        threadTs: String? = null,
        subtype: String? = null,
        botId: String? = null,
        snoozed: Boolean = false,
        settings: NotificationSettings = NotificationSettings(),
    ): List<Map<String, String>> {
        val directory = Files.createTempDirectory("relay-test").toFile()
        val devices = DeviceStore(directory.absolutePath)
        devices.register(Device(slackUserId = me, teamId = "T1", fcmToken = "token"))
        devices.updateSettings(me, settings)

        val sent = mutableListOf<Map<String, String>>()
        val router = EventRouter(devices)
        val delivery = DeliveryService(devices, RecordingSender(sent), AlwaysSnoozed(snoozed), InMemoryDeliveryStore())

        delivery.enqueue(router.plan(
            SlackEventEnvelope(
                type = "event_callback",
                teamId = "T1",
                eventId = "Ev1",
                authorizations = listOf(Authorization(userId = me, teamId = "T1", isBot = false)),
                event = SlackEvent(
                    type = "message",
                    subtype = subtype,
                    user = author,
                    text = text,
                    ts = "1757942460.000100",
                    channel = "C1",
                    channelType = channelType,
                    threadTs = threadTs,
                    botId = botId,
                ),
            ),
        ))
        while (delivery.processNext()) {}
        directory.deleteRecursively()
        return sent
    }
}

private class RecordingSender(private val sent: MutableList<Map<String, String>>) : PushSender {
    override val isReady = true
    override fun send(device: Device, payload: Map<String, String>): PushOutcome {
        sent += payload
        return PushOutcome.Accepted
    }
}

private class AlwaysSnoozed(private val snoozed: Boolean) : DndChecker {
    override fun isSnoozed(device: Device): Boolean = snoozed
}
