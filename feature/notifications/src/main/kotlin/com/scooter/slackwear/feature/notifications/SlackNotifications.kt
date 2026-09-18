package com.scooter.slackwear.feature.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object SlackNotifications {

    const val CHANNEL_MENTIONS = "mentions"
    const val CHANNEL_DIRECT_MESSAGES = "direct_messages"
    const val CHANNEL_THREADS = "threads"
    const val CHANNEL_ACTIVITY = "activity"

    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannels(
            listOf(
                channel(CHANNEL_MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH),
                channel(CHANNEL_DIRECT_MESSAGES, "Direct messages", NotificationManager.IMPORTANCE_HIGH),
                channel(CHANNEL_THREADS, "Thread replies", NotificationManager.IMPORTANCE_DEFAULT),

                channel(CHANNEL_ACTIVITY, "Channel activity", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    private fun channel(id: String, name: String, importance: Int) =
        NotificationChannel(id, name, importance).apply {
            enableVibration(importance >= NotificationManager.IMPORTANCE_DEFAULT)
        }
}
