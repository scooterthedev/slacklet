package com.scooter.slackwear.core.data

import com.scooter.slackwear.core.database.dao.ActivityDao
import com.scooter.slackwear.core.database.dao.ConversationDao
import com.scooter.slackwear.core.database.dao.UserDao
import com.scooter.slackwear.core.database.entity.UserEntity
import com.scooter.slackwear.core.database.entity.ActivityEntity
import com.scooter.slackwear.core.database.entity.ConversationEntity
import com.scooter.slackwear.core.model.repository.ActivityEntryInput
import com.scooter.slackwear.core.model.repository.ActivityItem
import com.scooter.slackwear.core.model.repository.ActivityMarkReadRequest
import com.scooter.slackwear.core.model.repository.ActivityMutationGateway
import com.scooter.slackwear.core.network.ClientApi
import com.scooter.slackwear.core.network.SlackApi
import com.scooter.slackwear.core.network.model.ActivityFeedResponse
import com.scooter.slackwear.core.network.model.ActivityViewsResponse
import com.scooter.slackwear.core.network.model.ClientBadgeCountsResponse
import com.scooter.slackwear.core.network.model.ClientSections
import com.scooter.slackwear.core.network.model.ResponseMetadata
import com.scooter.slackwear.core.network.model.SimpleResponse
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityThreadRegressionTest {
    @Test
    fun sectionsUseIdsAndPreserveEmptyHiddenAndRedacted() {
        val roster = listOf(json("""{"id":"C1","name":"general"}""").decodeClientConversation(0)!!)
        val config = decodeSections(ClientSections(channelSections = listOf(
            json("""{"channel_section_id":"S1","name":"Saved","channel_ids":["C1"],"channels":["wrong"],"is_hidden":true}"""),
            json("""{"channel_section_id":"S2","channel_ids":[],"is_redacted":true}"""),
        )), roster)
        assertEquals(listOf("general"), config.sections.first().channels)
        assertEquals(listOf("C1"), config.sections.first().channelIds)
        assertTrue(config.sections.first().isHidden)
        assertTrue(config.sections.last().isRedacted)
        assertEquals("S2", config.sections.last().id)
        assertTrue(decodeSections(ClientSections(), emptyList()).isConfigured)
        assertFalse(decodeSections(null, emptyList()).isConfigured)
    }

    @Test
    fun malformedPrimitiveFieldsDoNotThrow() {
        listOf("null", "{}", "[]").forEach { value ->
            val row = json("""{"id":"C1","latest":$value,"latest_ts":$value,"user":$value}""")
            assertNull(row.decodeClientConversation(0)!!.latestTs)
            val entry = json("""{"feed_ts":"3","item":{"type":"at_user","message":{"channel":$value,"ts":$value}}}""")
            assertNull(entry.decodeActivityEntry(emptyMap())!!.toActivityItem().target)
        }
        assertEquals("2", json("""{"id":"C1","latest":{"ts":"2"}}""").decodeClientConversation(0)!!.latestTs)
    }

    @Test
    fun activityKindsAndMessageIdentityAreNotFeedIdentity() {
        val kinds = mapOf(
            "at_user" to ActivityItem.Kind.MENTION,
            "at_user_group" to ActivityItem.Kind.USER_GROUP_MENTION,
            "at_channel" to ActivityItem.Kind.CHANNEL_MENTION,
            "at_everyone" to ActivityItem.Kind.EVERYONE_MENTION,
            "message_reaction" to ActivityItem.Kind.REACTION,
            "unknown_future" to ActivityItem.Kind.UNKNOWN,
        )
        kinds.forEach { (type, kind) ->
            val row = json("""{"key":"K","feed_ts":"30","is_unread":true,"item":{"type":"$type","message":{"channel":{"id":"C1"},"ts":"20","thread_ts":"10"}}}""")
                .decodeActivityEntry(emptyMap())!!.toActivityItem()
            assertEquals(kind, row.kind)
            assertEquals("30", row.feedTs)
            assertEquals("20", row.messageTs)
            assertEquals("10", row.target!!.threadTs)
            assertEquals("C1", row.target!!.conversationId)
            assertEquals("K", row.entryKey)
        }
    }

    @Test
    fun bundlePayloadsKeepThreadAndLatestPointers() {
        val thread = bundle("thread_v2", """"thread_entry":{"channel_id":"C1","latest_ts":"20","thread_ts":"10","unread_msg_count":4}""")
        assertEquals(ActivityItem.Kind.THREAD_REPLY, thread.kind)
        assertEquals("10", thread.threadTs)
        assertEquals("20", thread.messageTs)
        assertEquals(4, thread.unreadCount)
        val dm = bundle("dm", """"dm_entry":{"latest_message":{"channel":"D1","ts":"21"}}""")
        assertEquals(ActivityItem.Kind.DIRECT_MESSAGE, dm.kind)
        assertEquals("D1", dm.conversationId)
        val channel = bundle("channel", """"channel_entry":{"latest_message":{"channel":"C2","ts":"22"},"unread_msg_count":2}""")
        assertEquals(ActivityItem.Kind.CHANNEL, channel.kind)
        assertEquals("22", channel.messageTs)
        val bot = bundle("bot_dm_bundle", """"message":{"channel":"D2","ts":"23"}""")
        assertEquals(ActivityItem.Kind.BOT_DIRECT_MESSAGE, bot.kind)
        assertEquals("D2", bot.conversationId)
    }

    @Test
    fun viewFiltersAndPreferenceFallbackDecode() {
        val view = json("""{"id":"V1","name":"Mentions","filters":{"entry_types":["at_user"],"unread_only":true,"channel_ids":["C1"],"read_state":"unread"},"sort":"recent"}""")
            .decodeActivityView(json("""{"sort":"priority","density":"compact"}"""))!!
        assertEquals("recent", view.sort)
        assertEquals("compact", view.density)
        assertEquals(listOf("at_user"), view.filters.entryTypes)
        assertEquals(true, view.filters.unreadOnly)
    }

    @Test
    fun getViewRosterWorksWithoutGlobalGetAndFailurePreservesUnreads() = runTest {
        var fail = false
        val calls = mutableListOf<String>()
        val api = stub<ClientApi> { name, args ->
            calls += name
            assertEquals("threadsGetView", name)
            assertEquals("all", args[3])
            if (fail) json("""{"ok":false,"error":{}}""") else view()
        }
        val repository = InternalThreadRepository(api)
        assertTrue(repository.refresh().isSuccess)
        val before = repository.observeThreads().first()
        assertEquals(2, before.size)
        assertTrue(before.first().unread)
        assertEquals("reply", before.first().latestReplies.single().text)
        assertEquals(mapOf("U1" to "high"), before.first().priority)
        fail = true
        assertTrue(repository.refresh().isFailure)
        assertEquals(before, repository.observeThreads().first())
        assertEquals(listOf("threadsGetView", "threadsGetView"), calls)
    }

    @Test
    fun threadMarkSendsReadPositionAndUnfollowIsChannelScoped() = runTest {
        val api = stub<ClientApi> { name, args ->
            when (name) {
                "threadsGetView" -> view()
                "threadMark" -> {
                    assertEquals(listOf("C1", "10", "20", true), args.take(4))
                    SimpleResponse(ok = true)
                }
                "threadRemove" -> SimpleResponse(ok = true)
                else -> error(name)
            }
        }
        val repository = InternalThreadRepository(api)
        repository.refresh().getOrThrow()
        repository.markRead("C1", "10", "20").getOrThrow()
        assertFalse(repository.observeThreads().first().first { it.channelId == "C1" }.unread)
        repository.unfollow("C1", "10").getOrThrow()
        assertEquals("C2", repository.observeThreads().first().single().channelId)
    }

    @Test
    fun guessedUnreadFieldsAreIgnored() {
        val decoded = json("""{"threads":[{"root_msg":{"channel":"C1","ts":"10"},"unread_count":9,"is_unread":true,"unread_replies":[]}]}""").decodeThreadView()
        assertFalse(decoded.single().unread)
    }

    @Test
    fun feedUsesVerifiedDefaultAndExplicitCursorAndPropagatesFailures() = runTest {
        val cursors = mutableListOf<Any?>()
        var fail = false
        val api = stub<ClientApi> { name, args ->
            when (name) {
                "activityFeed" -> {
                    assertEquals("chrono_v1", args[0])
                    assertEquals(com.scooter.slackwear.core.network.model.defaultActivityTypes, args[5])
                    assertEquals(false, args[1])
                    assertEquals(false, args[7])
                    assertEquals(false, args[8])
                    cursors += args[6]
                    ActivityFeedResponse(ok = !fail, error = if (fail) "synthetic" else null,
                        responseMetadata = ResponseMetadata(nextCursor = if (args[6] == null) "next" else ""))
                }
                "activityViews" -> {
                    assertEquals(1, args.size)
                    ActivityViewsResponse()
                }
                "activityBadgeCounts" -> ClientBadgeCountsResponse()
                else -> error(name)
            }
        }
        val dao = stub<ActivityDao> { name, _ ->
            when (name) { "upsert", "trimTo" -> Unit; else -> error(name) }
        }
        val repository = InternalActivityRepository(api, noApi(), dao, namesDao(), usersDao())
        repository.refresh().getOrThrow()
        repository.loadMore().getOrThrow()
        repository.loadMore().getOrThrow()
        assertEquals(listOf(null, "next"), cursors)
        fail = true
        assertTrue(repository.refresh().isFailure)
    }

    @Test
    fun auxiliaryFailuresDoNotFailFeedAndRefreshResetsPagination() = runTest {
        val calls = mutableListOf<String>()
        val cursors = mutableListOf<Any?>()
        val api = stub<ClientApi> { name, args ->
            calls += name
            when (name) {
                "activityFeed" -> {
                    cursors += args[6]
                    ActivityFeedResponse(ok = true, responseMetadata = ResponseMetadata(nextCursor = "next"))
                }
                "activityViews" -> ActivityViewsResponse(ok = false, error = "not_allowed")
                "activityBadgeCounts" -> ClientBadgeCountsResponse(ok = false, error = "missing_scope")
                else -> error(name)
            }
        }
        val dao = stub<ActivityDao> { name, _ ->
            when (name) { "upsert", "trimTo" -> Unit; else -> error(name) }
        }
        val repository = InternalActivityRepository(api, noApi(), dao, namesDao(), usersDao())
        repository.refresh().getOrThrow()
        assertEquals(listOf("activityFeed"), calls)
        assertTrue(repository.views().isFailure)
        assertTrue(repository.badgeCounts().isFailure)
        assertTrue(repository.loadMore().isFailure)
        assertTrue(repository.observePageState().first().hasMore)
        assertNotNull(repository.observePageState().first().error)
        repository.refresh().getOrThrow()
        assertTrue(repository.loadMore().isFailure)
        assertEquals(listOf(null, "next", null, "next"), cursors)
    }

    @Test
    fun activityReadWritesOnlyAfterAcknowledgementAndPreservesEntryIdentity() = runTest {
        var fail = true
        val writes = mutableListOf<List<Any?>>()
        val gateway = object : ActivityMutationGateway {
            override suspend fun markRead(request: ActivityMarkReadRequest): Long? {
                if (fail) error("synthetic")
                return 7
            }
            override suspend fun markUnread(entries: List<ActivityEntryInput>) {
                if (fail) error("synthetic")
            }
        }
        val dao = stub<ActivityDao> { name, args ->
            writes += listOf(name) + args.take(2)
            Unit
        }
        val repository = InternalActivityRepository(stub<ClientApi> { name, _ -> error(name) }, noApi(), dao, namesDao(), usersDao(), gateway)
        val entry = ActivityEntryInput("thread_v2", "K", "30")
        val request = ActivityMarkReadRequest(entries = listOf(entry))
        assertTrue(repository.markRead(request).isFailure)
        assertTrue(repository.markUnread(listOf(entry)).isFailure)
        assertTrue(writes.isEmpty())
        fail = false
        assertEquals(7L, repository.markRead(request).getOrThrow())
        repository.markUnread(listOf(entry)).getOrThrow()
        assertEquals(listOf(listOf("markEntryRead", "K", "30"), listOf("markEntryUnread", "K", "30")), writes)
    }

    @Test
    fun mutationJsonUsesExactWireKeys() {
        val entry = ActivityEntryInput("thread_v2", "K", "30")
        val request = ActivityMarkReadRequest("thread_v2", "20", "10", "C1", "K", "30", listOf(entry), 7)
        assertEquals(json("""{"type":"thread_v2","ts":"20","thread_ts":"10","channel":"C1","key":"K","feed_ts":"30","entries":[{"type":"thread_v2","key":"K","feed_ts":"30"}],"undo_key":7}"""), request.toActivityJson())
        assertEquals(json("""{"entries":[{"type":"thread_v2","key":"K","feed_ts":"30"}]}"""), listOf(entry).toActivityUnreadJson())
    }

    @Test
    fun feedRetainsEveryPageBeyondTwoHundredRows() = runTest {
        val cached = kotlinx.coroutines.flow.MutableStateFlow(emptyList<ActivityEntity>())
        val dao = stub<ActivityDao> { name, args ->
            when (name) {
                "observeRecent" -> { assertEquals(-1, args[0]); cached }
                "upsert" -> {
                    @Suppress("UNCHECKED_CAST")
                    cached.value = (cached.value + args[0] as List<ActivityEntity>).distinctBy { it.id }
                    Unit
                }
                else -> error(name)
            }
        }
        val api = stub<ClientApi> { name, args ->
            assertEquals("activityFeed", name)
            val page = (args[6] as? String)?.toInt() ?: 0
            ActivityFeedResponse(items = (0 until 50).map {
                val id = page * 50 + it
                json("""{"key":"$id","feed_ts":"$id","is_unread":true,"item":{"type":"dm","channel":"D$id"}}""")
            }, responseMetadata = ResponseMetadata(nextCursor = if (page < 5) "${page + 1}" else null))
        }
        val repo = InternalActivityRepository(api, noApi(), dao, namesDao(), usersDao())
        repo.refresh().getOrThrow()
        repeat(5) { repo.loadMore().getOrThrow() }
        assertEquals(300, repo.observeFeed().first().size)
        assertFalse(repo.observePageState().first().hasMore)
        assertNull(repo.observePageState().first().error)
    }

    @Test
    fun genericChannelActivityIsHiddenFromCacheAndPagesWithoutHidingNotifications() = runTest {
        fun entry(type: String) = json("""{"key":"$type","feed_ts":"30","is_unread":true,"item":{"type":"$type","message":{"channel":"C1","ts":"20"}}}""")
        val cached = kotlinx.coroutines.flow.MutableStateFlow(listOf(entry("channel").decodeActivityEntry(emptyMap())!!))
        val dao = stub<ActivityDao> { name, args ->
            when (name) {
                "observeRecent" -> cached
                "upsert" -> {
                    @Suppress("UNCHECKED_CAST")
                    cached.value = (cached.value + args[0] as List<ActivityEntity>).distinctBy { it.id }
                    Unit
                }
                else -> error(name)
            }
        }
        val notifications = listOf(
            "at_user", "at_user_group", "at_channel", "at_everyone", "dm", "bot_dm_bundle",
            "thread_v2", "message_reaction", "keyword", "internal_channel_invite", "saved_reminder",
        )
        val cursors = mutableListOf<Any?>()
        val api = stub<ClientApi> { name, args ->
            assertEquals("activityFeed", name)
            cursors += args[6]
            ActivityFeedResponse(
                items = if (args[6] == null) listOf(entry("channel")) else notifications.map(::entry),
                responseMetadata = ResponseMetadata(nextCursor = if (args[6] == null) "next" else null),
            )
        }
        val repo = InternalActivityRepository(api, noApi(), dao, namesDao(), usersDao())
        assertTrue(repo.observeFeed().first().isEmpty())
        repo.refresh().getOrThrow()
        assertTrue(repo.observeFeed().first().isEmpty())
        assertTrue(repo.observePageState().first().hasMore)
        repo.loadMore().getOrThrow()
        val visible = repo.observeFeed().first()
        assertEquals(notifications.toSet(), visible.map { it.entryType }.toSet())
        assertTrue(visible.all { it.conversationId == "C1" })
        assertFalse(repo.observePageState().first().hasMore)
        repo.loadMore().getOrThrow()
        assertEquals(listOf(null, "next"), cursors)
    }

    @Test
    fun threadsUseLastRowLatestReplyBoundaryAndPreserveCanonicalIdentity() = runTest {
        val cursors = mutableListOf<Any?>()
        val api = stub<ClientApi> { _, args ->
            cursors += args[0]
            if (args[0] == null) {
                val rows = (1..200).joinToString(",") {
                    """{"root_msg":{"channel":"C$it","ts":"1","thread_ts":"2","latest_reply":"${1000 - it}"},"latest_replies":[{"ts":"3"}]}"""
                }
                json("""{"ok":true,"has_more":true,"threads":[$rows]}""")
            } else json("""{"ok":true,"has_more":false,"threads":[{"root_msg":{"channel":"C201","ts":"4","thread_ts":"5"},"unread_replies":[{"ts":"799"}]}]}""")
        }
        val repo = InternalThreadRepository(api)
        repo.refresh().getOrThrow()
        assertEquals("2", repo.observeThreads().first().first().threadTs)
        assertEquals("1", repo.observeThreads().first().first().rootMessage?.ts)
        repo.loadMore().getOrThrow()
        assertEquals(listOf(null, "800"), cursors)
        assertEquals(201, repo.observeThreads().first().size)
        assertFalse(repo.observePageState().first().hasMore)
    }

    @Test
    fun threadBoundaryFallbackAndRepeatedOrMissingBoundaryAreNotEnd() = runTest {
        for (replies in listOf(
            """"unread_replies":[{"ts":"30"},{"ts":"20"}],"latest_replies":[{"ts":"10"}]""",
            """"latest_replies":[{"ts":"30"},{"ts":"20"}]""",
        )) {
            val cursors = mutableListOf<Any?>()
            val repo = InternalThreadRepository(stub<ClientApi> { _, args ->
                cursors += args[0]
                json("""{"ok":true,"has_more":true,"threads":[{"root_msg":{"channel":"C1","ts":"1"},$replies}]}""")
            })
            repo.refresh().getOrThrow()
            assertTrue(repo.loadMore().isFailure)
            assertEquals(listOf(null, "20"), cursors)
            assertTrue(repo.observePageState().first().hasMore)
            assertNotNull(repo.observePageState().first().error)
            assertTrue(repo.loadMore().isFailure)
            assertEquals(2, cursors.size)
        }
        val repo = InternalThreadRepository(stub<ClientApi> { _, _ ->
            json("""{"ok":true,"has_more":true,"threads":[{"root_msg":{"channel":"C1","ts":"1"}}]}""")
        })
        assertTrue(repo.refresh().isFailure)
        assertTrue(repo.observePageState().first().hasMore)
        assertNotNull(repo.observePageState().first().error)
    }

    private fun bundle(type: String, payload: String): ActivityItem =
        json("""{"key":"K","feed_ts":"30","is_unread":true,"item":{"type":"$type","bundle_info":{"payload":{$payload}}}}""")
            .decodeActivityEntry(emptyMap())!!.toActivityItem()

    private fun view() = json("""{"ok":true,"has_more":false,"threads":[{"root_msg":{"channel":"C1","ts":"10","text":"root"},"latest_replies":[{"ts":"20","text":"reply"}],"unread_replies":[{"ts":"20"}],"priority":{"U1":"high"}},{"root_msg":{"channel":"C2","ts":"10"},"unread_replies":[]}]}""")

    private fun usersDao() = stub<UserDao> { name, args ->
        when (name) {
            "observeAll" -> kotlinx.coroutines.flow.flowOf(emptyList<UserEntity>())

            "existingIds" -> (args[0] as List<*>).filterIsInstance<String>()
            else -> error(name)
        }
    }

    private fun noApi() = stub<SlackApi> { name, _ -> error(name) }

    private fun namesDao() = stub<ConversationDao> { name, _ ->
        if (name == "observeForDisplay") {
            return@stub kotlinx.coroutines.flow.flowOf(emptyList<ConversationEntity>())
        }
        when (name) { "namesById" -> emptyList<Any>(); else -> error(name) }
    }
    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    private inline fun <reified T> stub(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
