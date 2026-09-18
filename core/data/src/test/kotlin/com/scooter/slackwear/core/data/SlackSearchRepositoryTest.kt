package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.model.repository.SearchHitKind
import com.scooter.slackwear.core.model.repository.SearchSort
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.model.SearchModulesRequest
import com.scooter.slackwear.core.network.model.SearchModulesResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SlackSearchRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val requests = mutableListOf<SearchModulesRequest>()
    private val lookups = mutableListOf<List<String>>()
    private var response = SearchModulesResponse()
    private var failure: Exception? = null
    private val clientApi = Proxy.newProxyInstance(
        ClientApi::class.java.classLoader,
        arrayOf(ClientApi::class.java),
    ) { _, method, arguments ->
        check(method.name == "searchModules")
        requests += arguments[0] as SearchModulesRequest
        failure?.let { throw it }
        response
    } as ClientApi
    private val userDao = Proxy.newProxyInstance(
        UserDao::class.java.classLoader,
        arrayOf(UserDao::class.java),
    ) { _, method, arguments ->
        check(method.name == "findByIds")
        val ids = (arguments[0] as List<*>).filterIsInstance<String>()
        lookups += ids
        listOf(UserEntity("U1", "Alice", "Alice", null, false)).filter { it.id in ids }
    } as UserDao
    private val repository = SlackSearchRepository(clientApi, userDao)

    @Test
    fun requestsMessagesModuleAndCorrectSortDirections() = runTest {
        for (sort in SearchSort.entries) {
            assertTrue(repository.search("synthetic", sort).isSuccess)
        }
        assertEquals(listOf("messages", "messages", "messages"), requests.map { it.module })
        assertEquals(listOf("timestamp", "timestamp", "score"), requests.map { it.sort })
        assertEquals(listOf("desc", "asc", "desc"), requests.map { it.sortDirection })
        assertTrue(requests.all { it.query == "synthetic" && it.count == 20L })
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun mapsGroupedMessagesWithParentChannelAndCachedAuthors() = runTest {
        response = json.decodeFromString(
            """{"ok":true,"module":"mixedResults","mixed_results":[{"module_type":"messages","results":[{"iid":"M1","team":"T1","channel":{"id":"C1","name":"general"},"messages":[{"ts":"1.000001","user":"U1","text":"hello"},{"ts":"2.000002","user":"U2","username":"Bob","text":"world"}]}]}]}""",
        )
        val hits = repository.search("synthetic", SearchSort.NEWEST).getOrThrow()
        assertEquals(listOf("1.000001", "2.000002"), hits.map { it.id })
        assertEquals(listOf("C1", "C1"), hits.map { it.conversationId })
        assertEquals(listOf("#general", "#general"), hits.map { it.conversationName })
        assertEquals(listOf("Alice", "Bob"), hits.map { it.authorName })
        assertEquals(listOf("hello", "world"), hits.map { it.text })
        assertEquals(listOf(listOf("U1", "U2")), lookups)
    }

    @Test
    fun mapsRawMixedRowsAndSkipsUnknownModulesAndInvalidValues() = runTest {
        response = json.decodeFromString(
            """{"ok":true,"mixed_results":[{"module_type":"channels","results":[null,42,"not an object",{},[{"id":"C1","name":"general"}]]},{"module_type":"people","results":[{"id":"U1","profile":{"display_name":"Alice"}},{"id":false},{"profile":{"display_name":"missing id"}}]},{"module_type":"files","results":[{"id":"F1","title":"notes"},{"id":{}}]},{"module_type":"future","results":[{"id":"X1"}]}]}""",
        )
        val hits = repository.search("synthetic", SearchSort.RELEVANCE).getOrThrow()
        assertEquals(listOf(SearchHitKind.CHANNEL, SearchHitKind.PERSON, SearchHitKind.FILE), hits.map { it.kind })
        assertEquals(listOf("C1", "U1", "F1"), hits.map { it.id })
        assertEquals(listOf("#general", "Alice", "notes"), hits.map { it.text })
    }

    @Test
    fun singleModuleAcceptsArrayOrObjectWithoutInventingMatchesWrapper() = runTest {
        for (items in listOf("""[{"id":"C1","name":"general"}]""", """{"id":"C1","name":"general"}""")) {
            response = json.decodeFromString("""{"ok":true,"module":"channels","items":$items}""")
            assertEquals("C1", repository.search("synthetic", SearchSort.NEWEST).getOrThrow().single().id)
        }
        response = json.decodeFromString("""{"ok":true,"module":"channels","items":{"matches":[{"id":"C1"}]}}""")
        assertTrue(repository.search("synthetic", SearchSort.NEWEST).getOrThrow().isEmpty())
    }

    @Test
    fun malformedMessageGroupsDoNotBecomeEmptyOrUnnavigableHits() = runTest {
        response = json.decodeFromString(
            """{"ok":true,"module":"messages","items":[{"channel":{"id":"C1"},"messages":[null,{},false,{"ts":7},{"ts":"1.000001","user":{},"text":[]}]},{"messages":[{"ts":"2.000002"}]},{"channel":{"id":false},"messages":[{"ts":"3.000003"}]},{"channel":{"id":"C2"},"messages":{}},{"matches":[{"ts":"4.000004"}]}]}""",
        )
        val hits = repository.search("synthetic", SearchSort.NEWEST).getOrThrow()
        assertEquals(listOf("1.000001"), hits.map { it.id })
        assertEquals("C1", hits.single().conversationId)
        assertEquals("", hits.single().authorName)
        assertEquals("", hits.single().text)
    }

    @Test
    fun itemsAreDecodedAgainstTheRequestedModuleWhenTheResponseNamesNone() = runTest {
        response = json.decodeFromString(
            """{"ok":true,"items":[{"channel":{"id":"C1","name":"general"},"messages":[{"ts":"1.000001","user":"U1","text":"hello"}]}]}""",
        )
        val hits = repository.search("synthetic", SearchSort.NEWEST).getOrThrow()
        assertEquals(listOf(SearchHitKind.MESSAGE), hits.map { it.kind })
        assertEquals("C1", hits.single().conversationId)
        assertEquals("hello", hits.single().text)
    }

    @Test
    fun blendedResponseToAMessagesRequestIsStillRead() = runTest {
        response = json.decodeFromString(
            """{"ok":true,"module":"mixedResults","mixed_results":[{"module_type":"channels","results":[{"id":"C9","name":"random"}]}]}""",
        )
        val hits = repository.search("synthetic", SearchSort.NEWEST).getOrThrow()
        assertEquals(listOf(SearchHitKind.CHANNEL), hits.map { it.kind })
        assertEquals("C9", hits.single().id)
    }

    @Test
    fun slackErrorsAreFailuresAndCancellationPropagates() = runTest {
        response = SearchModulesResponse(ok = false, error = "invalid_arguments")
        assertTrue(repository.search("synthetic", SearchSort.NEWEST).isFailure)
        failure = CancellationException("cancelled")
        val thrown = try {
            repository.search("synthetic", SearchSort.NEWEST)
            null
        } catch (error: CancellationException) {
            error
        }
        assertEquals("cancelled", thrown?.message)
    }
}
