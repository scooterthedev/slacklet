package com.scooter.slackwear.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.scooter.slackwear.SlackWearApplication
import com.scooter.slackwear.feature.notifications.PushNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PushNotifier.KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()

        val conversationId = intent.getStringExtra(PushNotifier.EXTRA_CONVERSATION_ID).orEmpty()
        if (text.isEmpty() || conversationId.isEmpty()) return

        val application = context.applicationContext as? SlackWearApplication ?: return
        val threadTs = intent.getStringExtra(PushNotifier.EXTRA_THREAD_TS)
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.container.messageRepository
                    .sendMessage(conversationId, text, threadTs)
                    .onFailure { Log.w(TAG, "Reply from notification failed", it) }

                NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.scooter.slackwear.ACTION_REPLY"
        private const val TAG = "NotificationReply"
    }
}
