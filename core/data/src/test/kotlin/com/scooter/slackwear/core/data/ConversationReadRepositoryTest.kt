package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ActivityDao
import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.SimpleResponse
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReadRepositoryTest {
    private val events = mutableListOf<String>()
    private var response = SimpleResponse(ok = true)
    private var failure: Exception? = null
    private val repository = SlackConversationRepository(
        clientApi = stub<ClientApi> { _, _ -> error("Unexpected client call") },
        slackApi = stub<SlackApi> { name, args ->
            assertEquals("markConversation", name)
            events += "server:${args[0]}:${args[1]}"
            failure?.let { throw it }
            response
        },
        conversationDao = stub<ConversationDao> { name, args ->
            assertEquals("acknowledgeRead", name)
            events += "local:${args[0]}:${args[1]}"
            Unit
        },
        activityDao = stub<ActivityDao> { _, _ -> error("Activity must not change") },
        userDao = stub<UserDao> { _, _ -> error("Users must not change") },
    )

    @Test
    fun acknowledgesOnlyAfterServerSuccess() = runBlocking {
        repository.markRead("C1", "1700000000.000001").getOrThrow()
        assertEquals(listOf("server:C1:1700000000.000001", "local:C1:1700000000.000001"), events)
    }

    @Test
    fun rejectedResponseLeavesLocalUntouchedAndCanRetry() = runBlocking {
        response = SimpleResponse(ok = false, error = "synthetic_failure")
        assertTrue(repository.markRead("C1", "1700000000.000001").isFailure)
        assertEquals(listOf("server:C1:1700000000.000001"), events)
        response = SimpleResponse(ok = true)
        repository.markRead("C1", "1700000000.000001").getOrThrow()
        assertEquals("local:C1:1700000000.000001", events.last())
    }

    @Test
    fun transportFailureAndInvalidTimestampDoNotWriteLocally() = runBlocking {
        failure = IllegalStateException("synthetic private details")
        assertTrue(repository.markRead("C1", "1700000000.000001").isFailure)
        listOf("local:1", "NaN", "1e9", "-1.000001", "0.000000").forEach {
            assertTrue(repository.markRead("C1", it).isFailure)
        }
        assertEquals(1, events.size)
    }

    @Test
    fun cancellationPropagatesWithoutLocalAcknowledgement() = runBlocking {
        failure = CancellationException("cancelled")
        var cancelled = false
        try {
            repository.markRead("C1", "1700000000.000001")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(listOf("server:C1:1700000000.000001"), events)
    }

    @Test
    fun duplicateOrOlderRequestsDoNotRegressServerCursor() = runBlocking {
        repository.markRead("C1", "1700000000.000002").getOrThrow()
        repository.markRead("C1", "1700000000.000001").getOrThrow()
        repository.markRead("C1", "1700000000.000002").getOrThrow()
        assertEquals(2, events.size)
    }

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
