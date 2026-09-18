package com.scooter.slackwear.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.scooter.slackwear.SlackWearApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DevProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? SlackWearApplication ?: return
        val api = application.container.slackApi

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { api.channelSections() }
            result.fold(
                onSuccess = { Log.i(TAG, "channelSections -> ${it.toString().take(1200)}") },
                onFailure = { Log.w(TAG, "channelSections failed: ${it.javaClass.simpleName}: ${it.message}") },
            )
        }
    }

    private companion object {
        const val TAG = "DevProbe"
    }
}
