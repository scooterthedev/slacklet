package com.scooter.slackwear.core.network

import com.scooter.slackwear.core.network.model.SearchModulesRequest
import com.scooter.slackwear.core.network.model.UserBootRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class ClientApiContractTest {
    private val server = MockWebServer()
    private val httpClient = OkHttpClient()
    private lateinit var clientApi: ClientApi
    private lateinit var slackApi: SlackApi

    @Before
    fun setUp() {
        server.start()
        val localCalls = Call.Factory { request ->
            httpClient.newCall(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }
        clientApi = SlackApiFactory.createClientApi(localCalls)
        slackApi = SlackApiFactory.create(localCalls)
    }

    @After
    fun tearDown() {
        server.close()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    @Test
    fun bootEncodesDefaultPlatformWithoutOtherDefaults() = runTest {
        respond("""{"ok":true,"is_open":["D1"],"starred":["C1"],"prefs":{"sidebar_theme":"dark"}}""")
        val response = clientApi.userBoot(UserBootRequest())
        val request = takePost("client.userBoot")
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertEquals(Json.parseToJsonElement("""{"platform":"android"}"""), Json.parseToJsonElement(request.body!!.utf8()))
        assertEquals(listOf("D1"), response.isOpen)
        assertEquals(listOf("C1"), response.starred)
        assertEquals(JsonPrimitive("dark"), response.prefs?.get("sidebar_theme"))
    }

    @Test
    fun bootPreservesExplicitPlatformAndOptionalFields() = runTest {
        respond()
        clientApi.userBoot(UserBootRequest(platform = "test", omitRestrictedChannels = false))
        val body = Json.parseToJsonElement(takePost("client.userBoot").body!!.utf8())
        assertEquals(Json.parseToJsonElement("""{"platform":"test","omit_restricted_channels":false}"""), body)
    }

    @Test
    fun activityViewsIsBodyless() = runTest {
        respond("""{"ok":true,"views":[],"prefs":{"sort":"newest"}}""")
        assertTrue(clientApi.activityViews().ok)
        assertEquals(0L, takePost("activity.views").bodySize)
    }

    @Test
    fun activityFeedDefaultsAndCursorMatchContract() = runTest {
        respond("""{"ok":true,"items":[],"response_metadata":{"next_cursor":"next"}}""")
        val response = clientApi.activityFeed(cursor = "cursor+value", limit = 20)
        assertEquals("next", response.responseMetadata?.nextCursor)
        assertEquals(
            mapOf(
                "mode" to "chrono_v1", "is_activity_inbox" to "true", "include_badge_counts" to "true",
                "types" to """["at_user","at_user_group","at_channel","at_everyone","bot_dm_bundle","external_channel_invite","external_dm_invite","internal_channel_invite","quietly_added_to_channel","keyword","message_reaction","list_record_edited","list_user_mentioned","list_record_assigned","thread_v2","saved_reminder","list_todo_notification","list_approval_request","list_approval_reviewed","dm","unjoined_channel_mention","prejoin_dm_welcome_party_alert"]""",
                "cursor" to "cursor+value", "limit" to "20", "unread_only" to "false",
                "archive_only" to "false", "priority_only" to "false", "only_salesforce_channels" to "false",
                "automations_only" to "false", "exclude_automations" to "false",
            ),
            takePost("activity.feed").form(),
        )
    }

    @Test
    fun activityFeedPreservesExplicitFalseFilters() = runTest {
        respond()
        clientApi.activityFeed(mode = "chrono_unreads", unreadOnly = true, isActivityInbox = false, includeBadgeCounts = false, archiveOnly = false, priorityOnly = true, channelIds = "C1,C2", channelSectionIds = "S1", onlySalesforceChannels = false, automationsOnly = false, excludeAutomations = true)
        assertEquals(
            mapOf("mode" to "chrono_unreads", "unread_only" to "true", "is_activity_inbox" to "false", "include_badge_counts" to "false", "archive_only" to "false", "priority_only" to "true", "channel_ids" to "C1,C2", "channel_section_ids" to "S1", "only_salesforce_channels" to "false", "automations_only" to "false", "exclude_automations" to "true", "types" to com.scooter.slackwear.core.network.model.defaultActivityTypes),
            takePost("activity.feed").form(),
        )
    }

    @Test
    fun threadMarkUsesReadPositionNotRootTimestamp() = runTest {
        respond()
        clientApi.threadMark("C1", "1700000000.000001", "1700000001.000002")
        assertEquals(
            mapOf("channel" to "C1", "thread_ts" to "1700000000.000001", "ts" to "1700000001.000002", "read" to "true"),
            takePost("subscriptions.thread.mark").form(),
        )
        respond()
        clientApi.threadMark("C1", "1700000000.000001", "1700000001.000002", read = false)
        assertEquals("false", takePost("subscriptions.thread.mark").form()["read"])
    }

    @Test
    fun threadAddSupportsOptionalLastRead() = runTest {
        respond()
        clientApi.threadAdd("C1", "1.000001")
        assertEquals(mapOf("channel" to "C1", "thread_ts" to "1.000001"), takePost("subscriptions.thread.add").form())
        respond()
        clientApi.threadAdd("C1", "1.000001", lastRead = "2.000002")
        assertEquals(mapOf("channel" to "C1", "thread_ts" to "1.000001", "last_read" to "2.000002"), takePost("subscriptions.thread.add").form())
    }

    @Test
    fun reactionsUseFormEncodedPlusOneAndExactMessageTimestamp() = runTest {
        for (add in listOf(true, false)) {
            respond()
            if (add) slackApi.addReaction("C1", "1700000000.000001", "+1")
            else slackApi.removeReaction("C1", "1700000000.000001", "+1")
            val request = takePost(if (add) "reactions.add" else "reactions.remove")
            assertTrue(request.body!!.utf8().contains("name=%2B1"))
            assertEquals(mapOf("channel" to "C1", "timestamp" to "1700000000.000001", "name" to "+1"), request.form())
        }
    }

    @Test
    fun searchDecodesRawObjectsAndArraysIncludingMixedRows() = runTest {
        respond("""{"ok":true,"module":"mixedResults","filters":{"from":["U1"]},"items":[{"id":"C1"}],"mixed_results":[{"module_type":"messages","results":[{"ts":"1.000001"},[1,2],null]}]}""")
        val response = clientApi.searchModules(SearchModulesRequest(module = "mixedResults", query = "test", sort = "timestamp", sortDirection = "asc"))
        assertTrue(response.filters is JsonObject)
        assertTrue(response.items is JsonArray)
        assertEquals(
            (Json.parseToJsonElement("""[{"ts":"1.000001"},[1,2],null]""") as JsonArray).toList(),
            response.mixedResults.single().results,
        )
        val body = Json.parseToJsonElement(takePost("search.modules").body!!.utf8()) as JsonObject
        assertEquals(JsonPrimitive("mixedResults"), body["module"])
        assertEquals(JsonPrimitive("asc"), body["sort_dir"])
        assertFalse(body.containsKey("highlight"))
    }

    @Test
    fun searchRetainsRawObjectItemsAndArrayFilters() = runTest {
        respond("""{"ok":true,"module":"channels","filters":[],"items":{"id":"C1","name":"test"}}""")
        val response = clientApi.searchModules(SearchModulesRequest(sortDirection = "desc"))
        assertTrue(response.filters is JsonArray)
        assertTrue(response.items is JsonObject)
        val body = Json.parseToJsonElement(takePost("search.modules").body!!.utf8()) as JsonObject
        assertEquals(JsonPrimitive("desc"), body["sort_dir"])
    }

    @Test
    fun searchAcceptsNullAndUnknownPayloadFields() = runTest {
        respond("""{"ok":true,"filters":null,"items":null,"mixed_results":[{"module_type":"future","results":null}],"future":{"x":1}}""")
        val response = clientApi.searchModules(SearchModulesRequest())
        assertTrue(response.items == null || response.items == JsonNull)
        assertTrue(response.mixedResults.single().results.isEmpty())
        takePost("search.modules")
    }

    private fun respond(body: String = """{"ok":true}""") {
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build())
    }

    private fun takePost(route: String): RecordedRequest {
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/$route", request.target)
        return request
    }

    private fun RecordedRequest.form(): Map<String, String> {
        assertEquals("application/x-www-form-urlencoded", headers["Content-Type"])
        return body!!.utf8().split('&').associate { field ->
            val (key, value) = field.split('=', limit = 2)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
    }
}
