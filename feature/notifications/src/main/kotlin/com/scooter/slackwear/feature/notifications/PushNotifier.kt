package com.scooter.slackwear.feature.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.getSystemService

class PushNotifier(
    private val context: Context,
    private val resolveTitle: (channelId: String, authorId: String) -> String,
    private val resolveText: (String) -> String = { it },
    private val openIntent: (channelId: String) -> Intent,
    private val replyIntent: (channelId: String, threadTs: String?, registrationId: String, teamId: String, userId: String) -> Intent,
) {

    fun show(payload: PushPayload) {
        val manager = context.getSystemService<android.app.NotificationManager>() ?: return
        SlackNotifications.createChannels(context)

        val sender = Person.Builder()
            .setName(resolveTitle(payload.channelId, payload.authorId))
            .setKey(payload.authorId.ifBlank { payload.channelId })
            .build()
        val body = resolveText(payload.preview)
        val style = NotificationCompat.MessagingStyle(sender)
            .setGroupConversation(false)
            .addMessage(body, payloadTs(payload), sender)

        val notification = NotificationCompat.Builder(context, payload.kind.channelId())
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(sender.name)
            .setContentText(body)
            .setStyle(style)
            .setWhen(payloadTs(payload))
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(payload.kind.priority())
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    payload.notificationId(),
                    openIntent(payload.channelId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(replyAction(payload))
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            manager.notify(payload.notificationId(), notification)
        }
    }

    fun status(id: Int, existing: Notification?, title: String, message: String) {
        val builder = existing?.let { NotificationCompat.Builder(context, it) }
            ?: NotificationCompat.Builder(context, SlackNotifications.CHANNEL_MENTIONS)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
        if (existing != null) {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(existing)?.let(builder::setStyle)
        }
        builder
            .setContentTitle(title)
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            context.getSystemService<android.app.NotificationManager>()?.notify(id, builder.build())
        }
    }

    private fun replyAction(payload: PushPayload): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply")
            .build()

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            payload.notificationId(),
            replyIntent(payload.channelId, payload.threadTs, payload.registrationId, payload.teamId, payload.slackUserId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun payloadTs(payload: PushPayload): Long = payload.ts.substringBefore('.').toLongOrNull()
        ?.times(1000)?.coerceAtLeast(0) ?: 0L

    companion object {
        const val KEY_REPLY = "reply_text"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_THREAD_TS = "threadTs"
        const val EXTRA_REGISTRATION_ID = "registrationId"
        const val EXTRA_TEAM_ID = "teamId"
        const val EXTRA_USER_ID = "userId"
    }
}

private fun PushKind.channelId(): String = when (this) {
    PushKind.MENTION -> SlackNotifications.CHANNEL_MENTIONS
    PushKind.DIRECT_MESSAGE -> SlackNotifications.CHANNEL_DIRECT_MESSAGES
    PushKind.THREAD_REPLY -> SlackNotifications.CHANNEL_THREADS
    PushKind.CHANNEL_ACTIVITY -> SlackNotifications.CHANNEL_ACTIVITY
}

private fun PushKind.priority(): Int = when (this) {
    PushKind.MENTION, PushKind.DIRECT_MESSAGE -> NotificationCompat.PRIORITY_HIGH
    PushKind.THREAD_REPLY -> NotificationCompat.PRIORITY_DEFAULT
    PushKind.CHANNEL_ACTIVITY -> NotificationCompat.PRIORITY_LOW
}
