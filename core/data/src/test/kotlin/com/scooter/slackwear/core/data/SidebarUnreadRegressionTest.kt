package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.ConversationPreviewPatch
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.database.dao.UnreadPatch
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.database.entity.mergeRoster
import com.scooter.slackwear.core.model.ConversationKind
import com.scooter.slackwear.core.model.UnreadState.Confidence
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.model.ClientBadgeCountsResponse
import com.scooter.slackwear.core.network.model.ClientChannelsResponse
import com.scooter.slackwear.core.network.model.ClientCountsChannel
import com.scooter.slackwear.core.network.model.ClientCountsResponse
import com.scooter.slackwear.core.network.model.ClientCountsSummaryResponse
import com.scooter.slackwear.core.network.model.ClientSections
import com.scooter.slackwear.core.network.model.UserBootResponse
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarUnreadRegressionTest {
    @Test
    fun exactDmCountSurvivesUnchangedReadWindow() {
        val patch = mergeUnreadCount(conversation(), counts(), 20)
        assertEquals(7, patch.count)
        assertEquals(Confidence.EXACT, patch.confidence)
    }

    @Test
    fun groupDmCountSurvivesUnchangedReadWindow() {
        val patch = mergeUnreadCount(conversation().copy(kind = ConversationKind.GROUP_MESSAGE), counts(), 20)
        assertEquals(7, patch.count)
        assertEquals(Confidence.EXACT, patch.confidence)
    }

    @Test
    fun advancedOrUnknownReadWindowCannotClaimExactCount() {
        listOf(
            counts().copy(latest = "200.000000"),
            counts().copy(lastRead = "099.000000"),
            counts().copy(lastRead = null),
            counts().copy(latest = null),
        ).forEach { row ->
            val patch = mergeUnreadCount(conversation(), row, 20)
            assertEquals(1, patch.count)
            assertEquals(Confidence.DERIVED, patch.confidence)
        }
    }

    @Test
    fun channelFlagIsDerivedDotAndMentionsAreBounded() {
        val patch = mergeUnreadCount(
            conversation().copy(kind = ConversationKind.PUBLIC_CHANNEL),
            counts().copy(mentionCount = Long.MAX_VALUE),
            20,
        )
        assertEquals(1, patch.count)
        assertEquals(Confidence.DERIVED, patch.confidence)
        assertEquals(Int.MAX_VALUE, patch.mentions)
    }

    @Test
    fun explicitReadClearsButAbsentFlagDoesNotInventZero() {
        assertEquals(0, mergeUnreadCount(conversation(), counts().copy(hasUnreads = false), 20).count)
        assertEquals(7, mergeUnreadCount(conversation(), counts().copy(hasUnreads = null), 20).count)
    }

    @Test
    fun staleCountsDoNotRegressLocalReadOrOrdering() {
        val stored = conversation().copy(unreadCount = 0, lastSeenTs = "100.000000")
        val patch = mergeUnreadCount(stored, counts(), 20)
        assertEquals(0, patch.count)
        assertNull(patch.lastSeen)
        assertNull(patch.latestTs)
        assertEquals(stored.refreshedAtMillis, patch.refreshedAtMillis)
    }

    @Test
    fun latestTimestampIsCarriedByUnreadBatch() {
        val patch = mergeUnreadCount(conversation(), counts().copy(latest = "200.000000"), 20)
        assertEquals("200.000000", patch.latestTs)
    }

    @Test
    fun rosterAbsentCountPreservesValidExactAndExplicitCountUpdatesIt() {
        val stored = conversation()
        val absent = requireNotNull(json("""{"id":"D1","is_im":true,"latest":"100.000000","last_read":"090.000000"}""").decodeClientConversation(20))
        assertEquals(7, stored.mergeRoster(absent, null).unreadCount)
        assertEquals(Confidence.EXACT, stored.mergeRoster(absent, null).unreadConfidence)
        assertEquals(9, stored.mergeRoster(stored.copy(unreadCount = 9), null).unreadCount)
        val advanced = stored.mergeRoster(absent.copy(latestTs = "200.000000"), null)
        assertEquals(Confidence.DERIVED, advanced.unreadConfidence)
        assertNull(advanced.latestPreview)
    }

    @Test
    fun rosterOmittedPreviewPreservesSameOrOlderWindowButClearsAdvancedWindow() {
        val stored = conversation()
        listOf(null, "090.000000", "100.000000", "200.000000").forEach { latest ->
            val incoming = stored.copy(latestTs = latest, latestPreview = null)
            val merged = stored.mergeRoster(incoming, null)
            assertEquals(if (latest == "200.000000") latest else stored.latestTs, merged.latestTs)
            assertEquals(if (latest == "200.000000") null else "preview", merged.latestPreview)
        }
    }

    @Test
    fun rosterSuppliedPreviewIsAcceptedOnlyForCurrentOrNewerTimestamp() {
        val stored = conversation()
        listOf(null, "090.000000", "100.000000", "200.000000").forEach { latest ->
            val incoming = stored.copy(latestTs = latest, latestPreview = "new preview")
            val merged = stored.mergeRoster(incoming, null)
            assertEquals(if (latest == "200.000000") latest else stored.latestTs, merged.latestTs)
            assertEquals(
                if (latest == "100.000000" || latest == "200.000000") "new preview" else "preview",
                merged.latestPreview,
            )
        }
        assertEquals("preview", stored.mergeRoster(stored.copy(latestTs = "200.000000"), null).latestPreview)
    }

    @Test
    fun bootDisplayCountIsExactOnlyWhenSupplied() {
        listOf("is_im", "is_mpim").forEach { kind ->
            listOf(null, "null", "0", "9").forEach { display ->
                val field = display?.let { ",\"unread_count_display\":$it" }.orEmpty()
                val boot = Json.decodeFromString<UserBootResponse>(
                    """{"ims":[{"id":"D1","$kind":true,"latest":"100.000000","last_read":"090.000000"$field}]}""",
                )
                val incoming = requireNotNull(boot.ims.single().decodeClientConversation(20))
                val exact = display == "0" || display == "9"
                assertEquals(if (exact) Confidence.EXACT else Confidence.DERIVED, incoming.unreadConfidence)
                assertEquals(display?.toIntOrNull() ?: 0, incoming.unreadCount)
                val merged = conversation().copy(kind = incoming.kind).mergeRoster(incoming, null)
                assertEquals(if (exact) display.toInt() else 7, merged.unreadCount)
                assertEquals(Confidence.EXACT, merged.unreadConfidence)
            }
        }
    }

    @Test
    fun deserializedOpenStatePreservesOmissionThroughRosterMerge() {
        listOf("", ",\"is_open\":null", ",\"is_open\":[]", ",\"is_open\":[\"D1\"]").forEach { field ->
            val body = """{"ims":[{"id":"D1","is_im":true}]$field}"""
            val boot = Json.decodeFromString<UserBootResponse>(body)
            val channels = requireNotNull(Json.decodeFromString<ClientChannelsResponse>("""{"channels":$body}""").channels)
            listOf(boot.ims to boot.isOpen, channels.ims to channels.isOpen).forEach { (rows, openIds) ->
                val states = rosterOpenStates(rows, openIds)
                val expected = when (field) {
                    ",\"is_open\":[]" -> mapOf("D1" to false)
                    ",\"is_open\":[\"D1\"]" -> mapOf("D1" to true)
                    else -> emptyMap()
                }
                assertEquals(expected, states)
                val incoming = requireNotNull(rows.single().decodeClientConversation(20))
                assertEquals(field != ",\"is_open\":[]", conversation().mergeRoster(incoming, states["D1"]).isOpen)
            }
        }
    }

    @Test
    fun omittedOpenStatePreservesButExplicitClosedCloses() {
        val stored = conversation()
        assertTrue(stored.mergeRoster(stored.copy(isOpen = false), null).isOpen)
        assertFalse(stored.mergeRoster(stored, false).isOpen)
        val rows = listOf(json("""{"id":"D1"}"""))
        assertTrue(rosterOpenStates(rows, null).isEmpty())
        assertEquals(mapOf("D1" to false), rosterOpenStates(rows, emptyList()))
        assertEquals(mapOf("D1" to true, "D2" to true), rosterOpenStates(rows, listOf("D1", "D2")))
        assertEquals(mapOf("D1" to false), rosterOpenStates(listOf(json("""{"id":"D1","is_open":false}""")), null))
    }

    @Test
    fun countsApplyOnceDespiteSummaryAndBadgeFailures() = runTest {
        val batches = java.util.Collections.synchronizedList(mutableListOf<List<UnreadPatch>>())
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val dao = stub<ConversationDao> { name, args ->
            when (name) {
                "findByIds" -> listOf(conversation())
                "updateUnreadBatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    batches += args[0] as List<UnreadPatch>
                    calls += "applied"
                    Unit
                }
                else -> error(name)
            }
        }
        val api = stub<ClientApi> { name, _ ->
            calls += name
            when (name) {
                "counts" -> ClientCountsResponse(ims = listOf(counts()))
                "countsSummary" -> ClientCountsSummaryResponse(ok = false, error = "synthetic")
                "badgeCounts" -> ClientBadgeCountsResponse(ok = false, error = "synthetic")
                else -> error(name)
            }
        }
        val result = InternalUnreadRepository(api, dao).refresh()
        assertTrue(result.isFailure)
        assertEquals(listOf("applied", "badgeCounts", "counts", "countsSummary"), calls.sorted())
        assertTrue(calls.indexOf("counts") < calls.indexOf("applied"))
        assertEquals(1, batches.size)
        assertEquals(7, batches.single().single().count)
        assertEquals(1, result.exceptionOrNull()!!.suppressed.size)
    }

    @Test
    fun previewsIncludeImsAndMpimsWithoutRosterReplacement() = runTest {
        var patches = emptyList<ConversationPreviewPatch>()
        val dao = stub<ConversationDao> { name, args ->
            when (name) {
                "upsertRoster" -> {
                    assertTrue((args[0] as List<*>).isEmpty())
                    Unit
                }
                "updateDirectPreviews" -> {
                    @Suppress("UNCHECKED_CAST")
                    patches = args[0] as List<ConversationPreviewPatch>
                    Unit
                }
                else -> error(name)
            }
        }
        val api = stub<ClientApi> { name, _ ->
            when (name) {
                "channels" -> ClientChannelsResponse()
                "dms" -> json("""{"ok":true,"ims":[null,{"id":"D1","message":{"ts":"100.000000","text":"hello"}}],"mpims":[{"id":"G1","message":{"ts":"200.000000","text":"group"}}]}""")
                else -> error(name)
            }
        }
        assertTrue(InternalSidebarRepository(api, dao, emptyUsers()).refresh().isSuccess)
        assertEquals(listOf("D1", "G1"), patches.map { it.id })
        assertEquals(listOf("hello", "group"), patches.map { it.preview })
    }

    @Test
    fun absentSectionsPreserveCacheAndFailedDmsCannotApplyPreviews() = runTest {
        var first = true
        var previewWrites = 0
        val dao = stub<ConversationDao> { name, _ ->
            when (name) {
                "upsertRoster" -> Unit
                "namesById" -> emptyList<Any>()
                "updateDirectPreviews" -> { previewWrites++; Unit }
                else -> error(name)
            }
        }
        val api = stub<ClientApi> { name, _ ->
            when (name) {
                "channels" -> ClientChannelsResponse(sections = if (first) ClientSections(
                    channelSections = listOf(json("""{"name":"Saved","channels":["general"],"channel_ids":["general"]}""")),
                ) else null)
                "dms" -> if (first) json("""{"ok":true}""") else json("""{"ok":false,"ims":[{"id":"D1","message":{"ts":"300.000000"}}]}""")
                else -> error(name)
            }
        }
        val repository = InternalSidebarRepository(api, dao, emptyUsers())
        assertTrue(repository.refresh().isSuccess)
        val sections = repository.sections().getOrThrow()
        assertTrue(sections.isConfigured)
        first = false
        assertTrue(repository.refresh().isFailure)
        assertEquals(sections, repository.sections().getOrThrow())
        assertEquals(1, previewWrites)
    }

    @Test
    fun missingUnreadMetadataResolvesAllKindsAndReportsPartialFailure() = runTest {
        val stored = mutableMapOf("D1" to conversation())
        var patches = emptyList<UnreadPatch>()
        val dao = stub<ConversationDao> { name, args ->
            when (name) {
                "findByIds" -> (args[0] as List<*>).mapNotNull { stored[it] }
                "insertIgnoring" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as List<ConversationEntity>).forEach { stored.putIfAbsent(it.id, it) }
                    Unit
                }
                "updateUnreadBatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    patches = args[0] as List<UnreadPatch>
                    Unit
                }
                else -> error(name)
            }
        }
        val rows = (1..205).map { ClientCountsChannel(id = "C$it", hasUnreads = true) } +
            ClientCountsChannel(id = "G1", mentionCount = 2) + ClientCountsChannel(id = "denied", hasUnreads = true)
        val api = stub<ClientApi> { name, _ ->
            when (name) {
                "counts" -> ClientCountsResponse(channels = rows, ims = listOf(counts()))
                "countsSummary" -> ClientCountsSummaryResponse()
                "badgeCounts" -> ClientBadgeCountsResponse()
                else -> error(name)
            }
        }
        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val slack = stub<com.scooter.slackwear.core.network.SlackApi> { name, args ->
            assertEquals("conversationInfo", name)
            val id = args[0] as String
            calls += id
            com.scooter.slackwear.core.network.model.ConversationInfoResponse(
                ok = id != "denied", error = "not_allowed",
                channel = com.scooter.slackwear.core.network.model.ConversationDto(
                    id = id, name = id, isChannel = id.startsWith("C"), isMpim = id == "G1",
                ),
            )
        }
        val repo = InternalUnreadRepository(api, dao, slack)
        assertTrue(repo.refresh().isFailure)
        assertEquals(207, calls.size)
        assertFalse(calls.contains("D1"))
        assertEquals(207, patches.size)
        assertEquals(ConversationKind.GROUP_MESSAGE, stored["G1"]?.kind)
        assertEquals(2, patches.single { it.id == "G1" }.mentions)
        assertEquals(Confidence.DERIVED, patches.single { it.id == "C205" }.confidence)
        assertEquals(208, repo.unreadChannels().getOrThrow().size)
        assertFalse(stored.containsKey("denied"))
    }

    private fun conversation() = ConversationEntity(
        id = "D1", name = "person", kind = ConversationKind.DIRECT_MESSAGE,
        topic = null, isMuted = false, isArchived = false, counterpartUserId = "U1",
        isOpen = true, latestPreview = "preview", latestTs = "100.000000",
        lastSeenTs = "090.000000", unreadCount = 7, mentionCount = 2,
        unreadConfidence = Confidence.EXACT, refreshedAtMillis = 10,
    )

    private fun counts() = ClientCountsChannel(
        id = "D1", hasUnreads = true, mentionCount = 2,
        lastRead = "090.000000", latest = "100.000000",
    )

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun emptyUsers() = stub<UserDao> { name, _ ->
        check(name == "allNames") { "Unexpected user call: $name" }
        emptyList<UserEntity>()
    }

    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
