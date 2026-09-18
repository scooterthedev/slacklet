package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.MessageDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.MessageEntity
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.MessageDto
import com.scooter.slackwear.core.network.model.ReactionDto
import com.scooter.slackwear.core.network.model.SimpleResponse
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackMessageReactionTest {
    private val dao = ReactionDao()
    private val calls = mutableListOf<List<Any?>>()
    private var response = SimpleResponse(ok = true)
    private var apiFailure: Exception? = null
    private val api = proxy<SlackApi> { name, args ->
        check(name == "addReaction" || name == "removeReaction") { "Unexpected API call: $name" }
        calls += listOf(name) + args.take(3)
        apiFailure?.let { throw it }
        response
    }
    private val repository = SlackMessageRepository(
        api = api,
        uploadClient = OkHttpClient(),
        messageDao = dao,
        userDao = proxy<UserDao> { name, _ -> error("Unexpected user lookup: $name") },
        currentUserId = { "ME" },
    )

    @Test
    fun lookupIncludesThreadReplyAndUsesCompositeIdentity() = runTest {
        val reply = message(threadTs = ROOT)
        dao.upsert(listOf(reply, reply.copy(conversationId = "C2", text = "other")))

        assertTrue(repository.observeMessages("C1").first().isEmpty())
        assertEquals(ROOT, repository.findMessage("C1", TS)!!.threadTs)
        assertEquals("other", repository.findMessage("C2", TS)!!.text)
        assertNull(repository.findMessage("C3", TS))
    }

    @Test
    fun addUpdatesOldReplyLocallyWithoutHistoryRefresh() = runTest {
        dao.upsert(listOf(message(threadTs = ROOT)))
        assertTrue(repository.toggleReaction("C1", TS, "party_parrot", true).isSuccess)

        assertEquals(listOf(listOf("addReaction", "C1", TS, "party_parrot")), calls)
        val reply = repository.observeThread("C1", ROOT).first().single()
        assertEquals(ROOT, reply.threadTs)
        assertEquals(1, reply.reactions.single().count)
        assertTrue(reply.reactions.single().includesMe)
        assertEquals(listOf("ME"), reactions().single().users)
    }

    @Test
    fun addAndRemovePreservePartialUserListsAndUnrelatedReactions() = runTest {
        val unrelated = ReactionDto("eyes", 3, listOf("U3"))
        dao.upsert(listOf(message(reactions = listOf(ReactionDto("+1", 8, listOf("U1", "U2")), unrelated))))

        assertTrue(repository.toggleReaction("C1", TS, "+1", true).isSuccess)
        val added = reactions().first { it.name == "+1" }
        assertEquals(9, added.count)
        assertEquals(listOf("U1", "U2", "ME"), added.users)
        assertEquals(unrelated, reactions().first { it.name == "eyes" })

        assertTrue(repository.toggleReaction("C1", TS, "+1", false).isSuccess)
        val removed = reactions().first { it.name == "+1" }
        assertEquals(8, removed.count)
        assertEquals(listOf("U1", "U2"), removed.users)
        assertFalse(repository.findMessage("C1", TS)!!.reactions.first { it.name == "+1" }.includesMe)
        assertEquals("removeReaction", calls.last().first())
    }

    @Test
    fun removingLastReactionDropsChip() = runTest {
        dao.upsert(listOf(message(reactions = listOf(ReactionDto("+1", 1, listOf("ME"))))))
        assertTrue(repository.toggleReaction("C1", TS, "+1", false).isSuccess)
        assertTrue(repository.findMessage("C1", TS)!!.reactions.isEmpty())
    }

    @Test
    fun alreadyReflectedAddDoesNotDoubleCountOrDuplicateUser() = runTest {
        dao.upsert(listOf(message(reactions = listOf(ReactionDto("+1", 4, listOf("ME", "U1"))))))
        assertTrue(repository.toggleReaction("C1", TS, "+1", true).isSuccess)
        assertEquals(4, reactions().single().count)
        assertEquals(1, reactions().single().users.count { it == "ME" })
    }

    @Test
    fun rejectedMutationDoesNotChangeLocalTarget() = runTest {
        val original = message()
        dao.upsert(listOf(original))
        response = SimpleResponse(ok = false, error = "not_reactable")

        assertTrue(repository.toggleReaction("C1", TS, "+1", true).isFailure)
        assertEquals(original, dao.find("C1", TS))
    }

    @Test
    fun localReconciliationFailureDoesNotReportRemoteFailure() = runTest {
        dao.upsert(listOf(message()))
        dao.failUpdate = true
        assertTrue(repository.toggleReaction("C1", TS, "+1", true).isSuccess)
        assertEquals(1, calls.size)
    }

    @Test
    fun compareAndSetRetryPreservesConcurrentMessageChanges() = runTest {
        dao.upsert(listOf(message()))
        dao.conflict = true
        assertTrue(repository.toggleReaction("C1", TS, "+1", true).isSuccess)
        assertEquals("edited meanwhile", dao.find("C1", TS)!!.text)
        assertTrue(repository.findMessage("C1", TS)!!.reactions.single().includesMe)
        assertEquals(2, dao.updateAttempts)
    }

    @Test
    fun cancellationIsNotConvertedToMutationFailure() = runTest {
        apiFailure = CancellationException("cancelled")
        try {
            repository.toggleReaction("C1", TS, "+1", true)
            error("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, dao.updateAttempts)
        }
    }

    private suspend fun reactions(): List<ReactionDto> = Json.decodeFromString(
        ListSerializer(ReactionDto.serializer()),
        dao.find("C1", TS)!!.reactionsJson,
    )

    private fun message(threadTs: String? = null, reactions: List<ReactionDto> = emptyList()) =
        MessageDto(ts = TS, user = "U1", text = "old message", threadTs = threadTs, reactions = reactions)
            .toEntity("C1")

    private class ReactionDao : MessageDao {
        val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
        var failUpdate = false
        var conflict = false
        var updateAttempts = 0
        override fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
            rows.map { all -> all.filter { it.conversationId == conversationId && it.threadTs == null } }
        override fun observeThread(conversationId: String, threadTs: String): Flow<List<MessageEntity>> =
            rows.map { all -> all.filter { it.conversationId == conversationId && (it.ts == threadTs || it.threadTs == threadTs) } }
        override suspend fun find(conversationId: String, ts: String) =
            rows.value.firstOrNull { it.conversationId == conversationId && it.ts == ts }
        override suspend fun upsert(messages: List<MessageEntity>) {
            messages.forEach { message ->
                rows.value = rows.value.filterNot { it.conversationId == message.conversationId && it.ts == message.ts } + message
            }
        }
        override suspend fun updateReactions(conversationId: String, ts: String, previous: String, updated: String): Int {
            updateAttempts++
            if (failUpdate) error("Cache unavailable")
            val row = find(conversationId, ts) ?: return 0
            if (conflict) {
                conflict = false
                upsert(listOf(row.copy(text = "edited meanwhile")))
                return 0
            }
            if (row.reactionsJson != previous) return 0
            upsert(listOf(row.copy(reactionsJson = updated)))
            return 1
        }
        override suspend fun delete(conversationId: String, ts: String) {
            rows.value = rows.value.filterNot { it.conversationId == conversationId && it.ts == ts }
        }
        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args.orEmpty())
        } as T

    private companion object {
        const val ROOT = "1700000000.000001"
        const val TS = "1700000001.123456"
    }
}
