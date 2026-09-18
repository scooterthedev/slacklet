package com.scooter.slackwear.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.scooter.slackwear.SlackWearApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DevSectionsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? SlackWearApplication ?: return
        val json = intent.getStringExtra(EXTRA_JSON)

        if (json.isNullOrBlank()) {
            Log.w(TAG, "No sections payload supplied")
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                application.container.settingsRepository.importSections(json)
                    .onSuccess { Log.i(TAG, "Sections imported") }
                    .onFailure { Log.w(TAG, "Sections payload rejected: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DevSections"
        const val EXTRA_JSON = "sections"
    }
}
