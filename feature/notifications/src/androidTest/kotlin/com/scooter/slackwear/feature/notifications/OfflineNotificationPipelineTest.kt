package com.scooter.slackwear.feature.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OfflineNotificationPipelineTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var service: LocalService

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        assertEquals(TEST_PACKAGE, context.packageName)
        assertEquals(TEST_PACKAGE, instrumentation.context.packageName)
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.INTERNET))
        assertNull(context.packageManager.resolveContentProvider("$TEST_PACKAGE.firebaseinitprovider", 0))
        assertTrue(FirebaseApp.getApps(context).isEmpty())
        val metadata = context.packageManager.getApplicationInfo(TEST_PACKAGE, PackageManager.GET_META_DATA).metaData
        assertFalse(metadata.getBoolean("firebase_messaging_auto_init_enabled", true))
        assertFalse(metadata.getBoolean("firebase_data_collection_default_enabled", true))
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(TEST_PACKAGE, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.VIBRATE))
        manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(manager.areNotificationsEnabled())
        cleanUpNotifications()
        service = LocalService(
            PushNotifier(
                context = context,
                resolveTitle = { channel, author -> "$author in $channel" },
                openIntent = { dummyIntent("NeverOpenedActivity") },
                replyIntent = { _, _ -> dummyIntent("NeverSentReceiver") },
            ),
        )
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) cleanUpNotifications()
    }

    @Test
    fun mentionPostsToHighImportanceChannel() {
        verifyKind(PushKind.MENTION, SlackNotifications.CHANNEL_MENTIONS,
            NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH)
    }

    @Test
    fun directMessagePostsToHighImportanceChannel() {
        verifyKind(PushKind.DIRECT_MESSAGE, SlackNotifications.CHANNEL_DIRECT_MESSAGES,
            NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH)
    }

    @Test
    fun threadReplyPostsToDefaultImportanceChannel() {
        verifyKind(PushKind.THREAD_REPLY, SlackNotifications.CHANNEL_THREADS,
            NotificationManager.IMPORTANCE_DEFAULT, NotificationCompat.PRIORITY_DEFAULT)
    }

    @Test
    fun upstreamAllowedChannelActivityPostsToLowImportanceChannel() {
        verifyKind(PushKind.CHANNEL_ACTIVITY, SlackNotifications.CHANNEL_ACTIVITY,
            NotificationManager.IMPORTANCE_LOW, NotificationCompat.PRIORITY_LOW)
    }

    @Test
    fun duplicatePayloadKeepsOneActiveNotification() {
        val message = remoteMessage(payload(PushKind.MENTION))
        service.onMessageReceived(message)
        val original = awaitNotification()
        service.onMessageReceived(message)
        assertCountRemains(1)
        assertEquals(original.key, manager.activeNotifications.single().key)
        assertEquals(2, service.delivered.size)
        assertEquals(service.delivered.first(), service.delivered.last())
    }

    @Test
    fun malformedPayloadsNeverPostOrReachDeliveryCallback() {
        val valid = payload(PushKind.MENTION)
        val invalid = listOf(
            valid - "channelId",
            valid - "ts",
            valid + ("channelId" to ""),
            valid + ("ts" to ""),
            valid + ("channelId" to " \t"),
            valid + ("ts" to " \n"),
            valid - "kind",
            valid + ("kind" to "UNKNOWN"),
        )
        invalid.forEach { data ->
            service.onMessageReceived(remoteMessage(data))
            assertCountRemains(0)
            assertTrue(service.delivered.isEmpty())
        }
    }

    private fun verifyKind(kind: PushKind, channelId: String, importance: Int, priority: Int) {
        val data = payload(kind)
        val message = remoteMessage(data)
        val start = SystemClock.elapsedRealtimeNanos()
        service.onMessageReceived(message)
        val posted = awaitNotification()
        samples += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val notification = posted.notification
        assertEquals(TEST_PACKAGE, posted.packageName)
        assertEquals(data.getValue("channelId").hashCode(), posted.id)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(channelId, notification.channelId)
        assertEquals(importance, manager.getNotificationChannel(channelId).importance)
        assertEquals(priority, notification.priority)
        assertEquals("synthetic-author in synthetic-channel", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(data["preview"], notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(data["preview"], notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
        assertNotNull(notification.contentIntent)
        assertEquals(TEST_PACKAGE, notification.contentIntent.creatorPackage)
        assertEquals(1, notification.actions.size)
        assertEquals(TEST_PACKAGE, notification.actions.single().actionIntent.creatorPackage)
        assertEquals(PushNotifier.KEY_REPLY, notification.actions.single().remoteInputs.single().resultKey)
        assertEquals(listOf(parsePushPayload(data)), service.delivered)
    }

    private fun payload(kind: PushKind) = mapOf(
        "kind" to kind.name,
        "channelId" to "synthetic-channel",
        "ts" to "1700000000.000001",
        "authorId" to "synthetic-author",
        "threadTs" to "1700000000.000000",
        "preview" to "Synthetic local preview",
    )

    private fun remoteMessage(data: Map<String, String>) =
        RemoteMessage.Builder("synthetic-local-sender").setData(data).build()

    private fun dummyIntent(name: String) = Intent().apply {
        component = ComponentName(TEST_PACKAGE, "$TEST_PACKAGE.$name")
    }

    private fun awaitNotification(): StatusBarNotification {
        val deadline = SystemClock.elapsedRealtimeNanos() + TIMEOUT_NANOS
        do {
            manager.activeNotifications.singleOrNull()?.let { return it }
            SystemClock.sleep(5)
        } while (SystemClock.elapsedRealtimeNanos() < deadline)
        throw AssertionError("Synthetic notification not active within 2 seconds")
    }

    private fun assertCountRemains(expected: Int) {
        val deadline = SystemClock.elapsedRealtimeNanos() + 300_000_000L
        do {
            assertEquals(expected, manager.activeNotifications.size)
            SystemClock.sleep(10)
        } while (SystemClock.elapsedRealtimeNanos() < deadline)
    }

    private fun cleanUpNotifications() {
        manager.activeNotifications.forEach { posted ->
            posted.notification.contentIntent?.cancel()
            posted.notification.actions?.forEach { it.actionIntent?.cancel() }
        }
        manager.cancelAll()
        val deadline = SystemClock.elapsedRealtimeNanos() + TIMEOUT_NANOS
        while (manager.activeNotifications.isNotEmpty() && SystemClock.elapsedRealtimeNanos() < deadline) {
            SystemClock.sleep(5)
        }
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private class LocalService(override val notifier: PushNotifier) : SlackMessagingService() {
        val delivered = mutableListOf<PushPayload>()

        override fun onTokenRotated(token: String) = error("Token registration must not run")

        override fun onPushDelivered(payload: PushPayload) {
            delivered += payload
        }
    }

    companion object {
        private const val TEST_PACKAGE = "com.scooter.slackwear.feature.notifications.offlinetest"
        private const val TIMEOUT_NANOS = 2_000_000_000L
        private val samples = mutableListOf<Double>()

        @JvmStatic
        @AfterClass
        fun printSyntheticLocalLatency() {
            if (samples.isEmpty()) return
            val formatted = samples.joinToString(",") { String.format(Locale.US, "%.3f", it) }
            Log.i("OfflinePushLatency", String.format(Locale.US,
                "Synthetic local handler-to-activeNotification ms: samples=[%s] n=%d min=%.3f mean=%.3f max=%.3f",
                formatted, samples.size, samples.min(), samples.average(), samples.max()))
            samples.clear()
        }
    }
}
