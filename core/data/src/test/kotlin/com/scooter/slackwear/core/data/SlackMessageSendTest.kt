package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.MessageDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.model.DeliveryState
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.MessageDto
import com.scooter.slackwear.core.network.model.PostMessageResponse
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SlackMessageSendTest {

    private val upserts = mutableListOf<List<MessageEntity>>()
    private val deletes = mutableListOf<Pair<String, String>>()
    private var response = PostMessageResponse(ok = true, ts = "1700000000.000200")
    private var apiFailure: Exception? = null
    private var knownUsers = emptyList<UserEntity>()

    private val dao = stub<MessageDao> { name, args ->
        when (name) {
            "upsert" -> {
                @Suppress("UNCHECKED_CAST")
                upserts += args[0] as List<MessageEntity>
                Unit
            }
            "delete" -> { deletes += (args[0] as String) to (args[1] as String); Unit }
            else -> error(name)
        }
    }

    private val users = stub<UserDao> { name, _ ->
        when (name) {
            "findByIds", "allNames" -> knownUsers
            "existingIds" -> knownUsers.map { it.id }
            else -> error(name)
        }
    }

    private val api = stub<SlackApi> { name, _ ->
        check(name == "postMessage") { "unexpected call $name" }
        apiFailure?.let { throw it }
        response
    }

    private fun repository() = SlackMessageRepository(
        api = api,
        uploadClient = OkHttpClient(),
        messageDao = dao,
        userDao = users,
        currentUserId = { "U-ME" },
    )

    @Test
    fun aMessageAppearsImmediatelyAsPendingBeforeTheNetworkIsTouched() = runTest {
        assertTrue(repository().sendMessage("C1", "hello", null).isSuccess)
        val pending = upserts.first().single()
        assertEquals(DeliveryState.PENDING, pending.deliveryState)
        assertEquals("hello", pending.text)
        assertEquals("U-ME", pending.authorId)
        assertEquals("C1", pending.conversationId)
    }

    @Test
    fun theServerTimestampReplacesTheOptimisticOneAndTheStubIsRemoved() = runTest {
        response = PostMessageResponse(ok = true, ts = "1700000000.000200")
        assertTrue(repository().sendMessage("C1", "hello", null).isSuccess)

        val confirmed = upserts.last().single()
        assertEquals("1700000000.000200", confirmed.ts)
        assertEquals(DeliveryState.SENT, confirmed.deliveryState)

        val pendingTs = upserts.first().single().ts
        assertEquals(listOf("C1" to pendingTs), deletes)
    }

    @Test
    fun theEchoedMessageFromSlackWinsOverWhatWeSent() = runTest {
        response = PostMessageResponse(
            ok = true,
            ts = "1700000000.000200",
            message = MessageDto(ts = "1700000000.000199", user = "U-ME", text = "hello *there*"),
        )
        assertTrue(repository().sendMessage("C1", "hello", null).isSuccess)
        val confirmed = upserts.last().single()
        assertEquals("1700000000.000200", confirmed.ts)
        assertEquals("message bodies keep their markup for the renderer", "hello *there*", confirmed.text)
    }

    @Test
    fun aThreadReplyKeepsItsThreadEvenWhenSlackEchoesWithoutOne() = runTest {
        response = PostMessageResponse(
            ok = true,
            ts = "1700000000.000200",
            message = MessageDto(ts = "1700000000.000200", user = "U-ME", text = "reply"),
        )
        assertTrue(repository().sendMessage("C1", "reply", "1699999999.000100").isSuccess)
        assertEquals("1699999999.000100", upserts.last().single().threadTs)
    }

    @Test
    fun aFailedSendLeavesTheMessageVisibleAndMarkedFailed() = runTest {
        apiFailure = IllegalStateException("synthetic transport failure")
        val result = repository().sendMessage("C1", "hello", null)
        assertTrue(result.isFailure)

        val last = upserts.last().single()
        assertEquals(DeliveryState.FAILED, last.deliveryState)
        assertEquals("hello", last.text)
        assertTrue("nothing should be deleted when the send failed", deletes.isEmpty())
    }

    @Test
    fun aSlackLevelErrorIsTreatedAsAFailedSendRatherThanASuccess() = runTest {
        response = PostMessageResponse(ok = false, error = "channel_not_found")
        val result = repository().sendMessage("C1", "hello", null)
        assertTrue(result.isFailure)
        assertEquals(DeliveryState.FAILED, upserts.last().single().deliveryState)
    }

    @Test
    fun aMissingServerTimestampFallsBackToTheOptimisticOneWithoutDeleting() = runTest {
        response = PostMessageResponse(ok = true, ts = null)
        assertTrue(repository().sendMessage("C1", "hello", null).isSuccess)

        val pendingTs = upserts.first().single().ts
        val confirmed = upserts.last().single()
        assertEquals(pendingTs, confirmed.ts)
        assertEquals(DeliveryState.SENT, confirmed.deliveryState)
        assertTrue("no delete when the timestamp did not change", deletes.isEmpty())
    }

    @Test
    fun mentionsInTheEchoAreResolvedToNamesForTheStoredCopy() = runTest {
        knownUsers = listOf(UserEntity("U08ALICE", "alice", "Alice", null, false))
        response = PostMessageResponse(
            ok = true,
            ts = "1700000000.000200",
            message = MessageDto(ts = "1700000000.000200", user = "U-ME", text = "ping <@U08ALICE>"),
        )
        assertTrue(repository().sendMessage("C1", "ping <@U08ALICE>", null).isSuccess)
        assertEquals("ping @alice", upserts.last().single().text)
    }

    @Test
    fun anOptimisticTimestampIsAValidSlackTimestamp() = runTest {
        repository().sendMessage("C1", "hello", null)
        val ts = upserts.first().single().ts
        assertTrue("'$ts' is not a Slack timestamp", Regex("[0-9]{1,10}\\.[0-9]{6}").matches(ts))
        assertFalse(ts.startsWith("0."))
    }

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
