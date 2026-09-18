package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.MessageDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.ConversationHistoryResponse
import com.scooter.slackwear.core.network.model.MessageDto
import com.scooter.slackwear.core.network.model.ResponseMetadata
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlackMessagePaginationTest {
    private val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val users = MutableStateFlow<List<UserEntity>>(emptyList())
    private val dao = object : MessageDao {
        override fun observeConversation(conversationId: String) =
            rows.map { all -> all.filter { it.conversationId == conversationId && it.threadTs == null } }
        override fun observeThread(conversationId: String, threadTs: String) =
            rows.map { all -> all.filter { it.conversationId == conversationId && (it.ts == threadTs || it.threadTs == threadTs) } }
        override suspend fun find(conversationId: String, ts: String) =
            rows.value.firstOrNull { it.conversationId == conversationId && it.ts == ts }
        override suspend fun upsert(messages: List<MessageEntity>) {
            messages.forEach { message ->
                rows.value = rows.value.filterNot { it.conversationId == message.conversationId && it.ts == message.ts } + message
            }
        }
        override suspend fun updateReactions(conversationId: String, ts: String, previous: String, updated: String) = 0
        override suspend fun delete(conversationId: String, ts: String) {
            rows.value = rows.value.filterNot { it.conversationId == conversationId && it.ts == ts }
        }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    private val requests = mutableListOf<Pair<Int, String?>>()
    private val history = MutableStateFlow(ConversationHistoryResponse(ok = true))
    private var historyFailure: Exception? = null
    private var apiGate: CompletableDeferred<Unit>? = null
    private var apiEntered = CompletableDeferred<Unit>()
    private suspend fun fetch(limit: Int, cursor: String?): ConversationHistoryResponse {
        requests += limit to cursor
        apiEntered.complete(Unit)
        apiGate?.await()
        historyFailure?.let { throw it }
        return history.value
    }
    private val api = object : SlackApi by proxy<SlackApi>({ name, _ -> error(name) }) {
        override suspend fun conversationHistory(
            channel: String,
            limit: Int,
            cursor: String?,
            oldest: String?,
            latest: String?,
            inclusive: Boolean?,
        ) = fetch(limit, cursor)
        override suspend fun conversationReplies(channel: String, ts: String, limit: Int, cursor: String?) =
            fetch(limit, cursor)
    }
    private val repository = SlackMessageRepository(
        api = api,
        uploadClient = OkHttpClient(),
        messageDao = dao,
        userDao = proxy<UserDao> { name, _ ->
            when (name) {
                "observeAll" -> users
                "existingIds" -> emptyList<String>()
                else -> Unit
            }
        },
        currentUserId = { "ME" },
    )

    private fun page(ts: String) = MessageDto(ts = ts, user = "U1", text = "m")

    private fun response(messages: List<MessageDto>, cursor: String?) = ConversationHistoryResponse(
        ok = true,
        messages = messages,
        responseMetadata = cursor?.let { ResponseMetadata(nextCursor = it) },
        hasMore = cursor != null,
    )

    @Test
    fun initialThenPagesUntilServerExhaustion() = runTest {
        history.value = response((31..60).reversed().map { page("1700000000.${it.toString().padStart(6, '0')}") }, "c1")
        assertTrue(repository.loadHistory("C1").isSuccess)
        assertEquals(30, repository.observeMessages("C1").first().size)
        val initial = repository.historyPagination("C1").value.initialPageTimestamps
        history.value = response((1..30).reversed().map { page("1700000000.${it.toString().padStart(6, '0')}") }, "c2")
        assertTrue(repository.loadOlderHistory("C1").isSuccess)
        history.value = response(emptyList(), null)
        assertTrue(repository.loadOlderHistory("C1").isSuccess)
        val state = repository.historyPagination("C1").value
        assertEquals(60, repository.observeMessages("C1").first().size)
        assertEquals(initial, state.initialPageTimestamps)
        assertTrue(state.endReached)
        assertFalse(state.error != null)
        assertEquals(listOf(30 to null, 30 to "c1", 30 to "c2"), requests)
    }

    @Test
    fun threadPagingWalksCursorForwardUntilExhaustion() = runTest {
        val root = page("1700000000.000001")
        history.value = response(listOf(root) + (1..49).map { page("1700000001.${it.toString().padStart(6, '0')}").copy(threadTs = root.ts) }, "t1")
        assertTrue(repository.loadThread("C1", "1700000000.000001").isSuccess)
        assertEquals(50, repository.observeThread("C1", root.ts).first().size)
        history.value = response((50..75).map { page("1700000002.${it.toString().padStart(6, '0')}").copy(threadTs = root.ts) }, "t2")
        assertTrue(repository.loadMoreReplies("C1", "1700000000.000001").isSuccess)
        history.value = response(emptyList(), null)
        assertTrue(repository.loadMoreReplies("C1", "1700000000.000001").isSuccess)
        assertTrue(repository.threadPagination("C1", "1700000000.000001").value.endReached)
        assertEquals(76, repository.observeThread("C1", root.ts).first().size)
        assertEquals(50, repository.threadPagination("C1", root.ts).value.initialPageTimestamps!!.size)
        assertEquals(listOf(50 to null, 50 to "t1", 50 to "t2"), requests)
    }

    @Test
    fun failureKeepsCursorForRetry() = runTest {
        history.value = response((1..30).map { page("170000000$it") }, "c1")
        assertTrue(repository.loadHistory("C1").isSuccess)
        historyFailure = java.io.IOException("offline")
        assertTrue(repository.loadOlderHistory("C1").isFailure)
        assertEquals("c1", repository.historyPagination("C1").value.error.let { requests.last().second })
        historyFailure = null
        history.value = response(emptyList(), null)
        assertTrue(repository.loadOlderHistory("C1").isSuccess)
        assertEquals(30 to "c1", requests[requests.size - 2])
    }

    @Test
    fun duplicateRowsCollapseAndRepeatedCursorFailsWithState() = runTest {
        val duplicates = (1..30).map { page("170000000$it") }
        history.value = response(duplicates + duplicates, "c1")
        assertTrue(repository.loadHistory("C1").isSuccess)
        assertEquals(30, repository.observeMessages("C1").first().size)
        history.value = response(duplicates.take(5), "c1")
        assertTrue(repository.loadOlderHistory("C1").isFailure)
        assertTrue(repository.historyPagination("C1").value.error != null)
        assertFalse(repository.historyPagination("C1").value.canLoadNext)
    }

    @Test
    fun emptyPageWithoutCursorEndsPagination() = runTest {
        history.value = response((1..30).map { page("170000000$it") }, "c1")
        assertTrue(repository.loadHistory("C1").isSuccess)
        history.value = response(emptyList(), null)
        assertTrue(repository.loadOlderHistory("C1").isSuccess)
        assertTrue(repository.historyPagination("C1").value.endReached)
        assertEquals(30, repository.observeMessages("C1").first().size)
    }

    @Test
    fun concurrentLoadIsRejectedWhilePageInFlight() = runTest {
        history.value = response((1..30).map { page("170000000$it") }, "c1")
        assertTrue(repository.loadHistory("C1").isSuccess)
        val gate = CompletableDeferred<Unit>()
        apiGate = gate
        apiEntered = CompletableDeferred()
        history.value = response(emptyList(), null)
        val first = async { repository.loadOlderHistory("C1") }
        apiEntered.await()
        val second = repository.loadOlderHistory("C1")
        assertTrue(second.isFailure)
        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
    }

    @Test
    fun cancellationDuringPageLoadLeavesItRetriable() = runTest {
        history.value = response((1..30).map { page("170000000$it") }, "c1")
        assertTrue(repository.loadHistory("C1").isSuccess)
        apiGate = CompletableDeferred()
        apiEntered = CompletableDeferred()
        val job = launch { repository.loadOlderHistory("C1") }
        apiEntered.await()
        assertTrue(repository.historyPagination("C1").value.isLoading)
        job.cancelAndJoin()
        assertFalse(repository.historyPagination("C1").value.isLoading)
        apiGate = null
        history.value = response(emptyList(), null)
        assertTrue(repository.loadOlderHistory("C1").isSuccess)
    }

    @Test
    fun emptyAndDuplicatePagesWithNewCursorsKeepGoing() = runTest {
        val row = page("1700000000.000001")
        history.value = response(listOf(row), "a")
        repository.loadHistory("C1").getOrThrow()
        history.value = response(emptyList(), "b")
        repository.loadOlderHistory("C1").getOrThrow()
        assertTrue(repository.historyPagination("C1").value.canLoadNext)
        history.value = response(listOf(row), "c")
        repository.loadOlderHistory("C1").getOrThrow()
        assertEquals(1, repository.observeMessages("C1").first().size)
        assertEquals(3, repository.historyPagination("C1").value.pagesLoaded)
        history.value = response(emptyList(), null)
        repository.loadOlderHistory("C1").getOrThrow()
        repository.loadOlderHistory("C1").getOrThrow()
        assertEquals(listOf(null, "a", "b", "c"), requests.map { it.second })
    }

    @Test
    fun cursorCycleAndMissingCursorAreRetriableNotExhaustion() = runTest {
        history.value = response(emptyList(), "a")
        repository.loadHistory("C1").getOrThrow()
        history.value = response(emptyList(), "b")
        repository.loadOlderHistory("C1").getOrThrow()
        history.value = response(emptyList(), "a")
        assertTrue(repository.loadOlderHistory("C1").isFailure)
        history.value = response(emptyList(), null).copy(hasMore = true)
        assertTrue(repository.loadOlderHistory("C1").isFailure)
        assertFalse(repository.historyPagination("C1").value.endReached)
        history.value = response(emptyList(), null)
        repository.loadOlderHistory("C1").getOrThrow()
        assertEquals(listOf(null, "a", "b", "b", "b"), requests.map { it.second })
    }

    @Test
    fun metadataUsesPublicWireNamesAndMissingValuesStayUnknown() = runTest {
        val dto = kotlinx.serialization.json.Json.decodeFromString<MessageDto>(
            """{"ts":"1700000000.000001","subscribed":true,"reply_count":2,"last_read":"1700000000.000002","latest_reply":"1700000000.000003"}""",
        )
        history.value = response(listOf(dto), null)
        repository.loadThread("D1", dto.ts).getOrThrow()
        val root = repository.findMessage("D1", dto.ts)!!
        assertEquals(true, root.isSubscribed)
        assertEquals(2, root.threadReplyCount)
        assertEquals(dto.lastRead, root.lastReadTs)
        assertEquals(dto.latestReply, root.latestReplyTs)
        history.value = response(listOf(MessageDto(ts = dto.ts)), null)
        repository.loadThread("D1", dto.ts).getOrThrow()
        assertEquals(null, repository.findMessage("D1", dto.ts)!!.isSubscribed)
        assertEquals(null, repository.findMessage("D1", dto.ts)!!.threadReplyCount)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T
}
