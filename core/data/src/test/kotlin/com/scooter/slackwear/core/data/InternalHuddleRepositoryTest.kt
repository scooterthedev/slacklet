package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.model.HuddleGetResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class InternalHuddleRepositoryTest {

    private var indirect: JsonObject = obj("{}")
    private var huddle: JsonObject? = null
    private val calls = mutableListOf<String>()
    private var failWith: Throwable? = null

    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    private fun api() = stub<ClientApi> { name, _ ->
        calls += name
        failWith?.let { throw it }
        when (name) {
            "huddleGetIndirect" -> indirect
            "huddleGet" -> HuddleGetResponse(huddle = huddle)
            "huddleKnock", "huddleCancelKnock" -> obj("""{"ok":true}""")
            else -> error(name)
        }
    }

    private fun users(vararg named: Pair<String, String>) = stub<UserDao> { name, _ ->
        when (name) {
            "findByIds" -> named.map { (id, display) -> UserEntity(id, display, display, null, false) }
            else -> error(name)
        }
    }

    private fun repository(dao: UserDao = users()) = InternalHuddleRepository(api(), dao)

    @Test
    fun noHuddleInTheChannelLeavesNoState() = runTest {
        indirect = obj("""{"ok":true}""")
        val repo = repository()
        assertTrue(repo.refresh("C1").isSuccess)
        assertNull(repo.observeHuddle("C1").first())
        assertFalse("huddleGet should not be called when there is no huddle id", calls.contains("huddleGet"))
    }

    @Test
    fun anActiveHuddleIsReportedWithItsParticipants() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        huddle = obj("""{"id":"H1","participants":["U1","U2"],"is_active":true}""")
        val repo = repository(users("U1" to "alice", "U2" to "bob"))
        assertTrue(repo.refresh("C1").isSuccess)

        val state = repo.observeHuddle("C1").first()!!
        assertEquals("H1", state.huddleId)
        assertEquals("C1", state.conversationId)
        assertTrue(state.active)
        assertEquals(2, state.participantCount)
        assertEquals(listOf("alice", "bob"), state.participantNames)
    }

    @Test
    fun aParticipantWeHaveNoNameForFallsBackToTheirId() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        huddle = obj("""{"id":"H1","participants":["U1","U-UNKNOWN"]}""")
        val repo = repository(users("U1" to "alice"))
        repo.refresh("C1")
        assertEquals(listOf("alice", "U-UNKNOWN"), repo.observeHuddle("C1").first()!!.participantNames)
    }

    @Test
    fun aBlankDisplayNameIsTreatedAsMissing() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        huddle = obj("""{"id":"H1","participants":["U1"]}""")
        val repo = repository(users("U1" to "   "))
        repo.refresh("C1")
        assertEquals(listOf("U1"), repo.observeHuddle("C1").first()!!.participantNames)
    }

    @Test
    fun theHuddleIdIsFoundWhicheverShapeSlackUses() = runTest {
        for (raw in listOf(
            """{"ok":true,"huddle_id":"H1"}""",
            """{"ok":true,"huddle":{"id":"H1"}}""",
            """{"ok":true,"huddle":{"huddle_id":"H1"}}""",
            """{"ok":true,"huddle":{"channel_id":"H1"}}""",
        )) {
            indirect = obj(raw)
            huddle = obj("""{"id":"H1","participants":["U1"]}""")
            val repo = repository(users("U1" to "alice"))
            repo.refresh("C1")
            assertEquals(raw, "H1", repo.observeHuddle("C1").first()!!.huddleId)
        }
    }

    @Test
    fun participantsAreReadFromWhicheverFieldSlackPopulates() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        for (field in listOf("participants", "member_ids", "user_ids", "active_participants")) {
            huddle = obj("""{"id":"H1","$field":["U1","U2"]}""")
            val repo = repository(users("U1" to "alice", "U2" to "bob"))
            repo.refresh("C1")
            assertEquals(field, 2, repo.observeHuddle("C1").first()!!.participantCount)
        }
    }

    @Test
    fun aHuddleThatHasEndedIsNotActive() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        huddle = obj("""{"id":"H1","participants":["U1"],"date_ended":"1700000000"}""")
        val repo = repository(users("U1" to "alice"))
        repo.refresh("C1")
        assertFalse(repo.observeHuddle("C1").first()!!.active)
    }

    @Test
    fun aHuddleWithNoIdentityAtAllClearsTheState() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        huddle = obj("""{"participants":["U1"]}""")
        val repo = repository(users("U1" to "alice"))
        assertTrue(repo.refresh("C1").isSuccess)
        assertNull(repo.observeHuddle("C1").first())
    }

    @Test
    fun aSlackErrorSurfacesAsAFailureWithoutPublishingState() = runTest {
        indirect = obj("""{"ok":false,"error":"channel_not_found"}""")
        val repo = repository()
        val result = repo.refresh("C1")
        assertTrue(result.isFailure)
        assertNull(repo.observeHuddle("C1").first())
    }

    @Test
    fun knockAndCancelReportSuccessAndFailureFromTheApi() = runTest {
        val repo = repository()
        assertTrue(repo.knock("C1").isSuccess)
        assertTrue(repo.cancelKnock("C1").isSuccess)

        failWith = IllegalStateException("synthetic transport failure")
        assertTrue(repository().knock("C1").isFailure)
        assertTrue(repository().cancelKnock("C1").isFailure)
    }

    @Test
    fun aHuddleIsOnlyObservedInTheConversationItBelongsTo() = runTest {
        indirect = obj("""{"ok":true,"huddle_id":"H1"}""")
        huddle = obj("""{"id":"H1","participants":["U1"]}""")
        val repo = repository(users("U1" to "alice"))
        repo.refresh("C1")
        assertNull(repo.observeHuddle("C-OTHER").first())
    }

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
