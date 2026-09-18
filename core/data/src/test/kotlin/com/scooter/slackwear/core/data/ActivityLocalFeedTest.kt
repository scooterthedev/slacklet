package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ActivityDao
import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.UnreadState
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApi
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLocalFeedTest {

    private val conversations = listOf(
        conversation("D1", "alice", ConversationKind.DIRECT_MESSAGE, unread = 2, counterpart = "U1"),
        conversation("G1", "trio", ConversationKind.GROUP_MESSAGE, unread = 1),
        conversation("C1", "log", ConversationKind.PUBLIC_CHANNEL, unread = 40),
        conversation("C2", "horizons-help", ConversationKind.PRIVATE_CHANNEL, unread = 7),
        conversation("C3", "lounge", ConversationKind.PUBLIC_CHANNEL, unread = 0),
    )

    private val repository = SlackConversationRepository(
        clientApi = stub<ClientApi> { _, _ -> error("Unexpected client call") },
        slackApi = stub<SlackApi> { _, _ -> error("Unexpected api call") },
        conversationDao = stub<ConversationDao> { name, _ ->
            check(name == "observeForDisplay") { "Unexpected dao call: $name" }
            flowOf(conversations)
        },
        activityDao = stub<ActivityDao> { name, _ ->
            check(name == "observeRecent") { "Unexpected activity call: $name" }
            flowOf(emptyList<Any>())
        },
        userDao = stub<UserDao> { name, _ ->
            check(name == "observeConversationCounterparts") { "Unexpected user call: $name" }
            flowOf(emptyList<Any>())
        },
    )

    @Test
    fun unreadChannelsAreNotActivityButUnreadDirectMessagesAre() = runTest {
        val activity = repository.observeActivity().first()

        assertEquals(listOf("D1", "G1"), activity.map { it.conversationId })
        assertEquals(
            listOf(ActivityItem.Kind.DIRECT_MESSAGE, ActivityItem.Kind.DIRECT_MESSAGE),
            activity.map { it.kind },
        )
    }

    private fun conversation(
        id: String,
        name: String,
        kind: ConversationKind,
        unread: Int,
        counterpart: String? = null,
    ) = ConversationEntity(
        id = id,
        name = name,
        kind = kind,
        topic = null,
        isMuted = false,
        isArchived = false,
        counterpartUserId = counterpart,
        isOpen = true,
        latestPreview = "hello",
        latestTs = "1700000000.00000$unread",
        lastSeenTs = null,
        unreadCount = unread,
        mentionCount = 0,
        unreadConfidence = UnreadState.Confidence.EXACT,
        refreshedAtMillis = 0,
    )

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
}
