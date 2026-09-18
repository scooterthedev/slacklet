package com.scooter.slackwear.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.scooter.slackwear.SlackWearApplication
import com.scooter.slackwear.core.auth.SlackSession
import com.scooter.slackwear.core.auth.SlackTokenType

class DevSignInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val application = context.applicationContext as? SlackWearApplication ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN)

        if (token.isNullOrBlank()) {
            application.container.tokenStore.clear()
            Log.i(TAG, "Session cleared")
            return
        }

        application.container.tokenStore.save(
            SlackSession(
                accessToken = token,
                userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty(),
                teamId = intent.getStringExtra(EXTRA_TEAM_ID).orEmpty(),
                teamName = intent.getStringExtra(EXTRA_TEAM_NAME).orEmpty(),
                tokenType = tokenType(intent.getStringExtra(EXTRA_TOKEN_TYPE)),
                secondaryToken = intent.getStringExtra(EXTRA_SECONDARY_TOKEN),
                teamDomain = intent.getStringExtra(EXTRA_TEAM_DOMAIN),
            ),
        )
        Log.i(TAG, "Session stored for ${intent.getStringExtra(EXTRA_TEAM_NAME)}")
    }

    private fun tokenType(raw: String?): SlackTokenType =
        if (raw.equals("CLIENT", ignoreCase = true)) SlackTokenType.CLIENT else SlackTokenType.USER

    private companion object {
        const val TAG = "DevSignIn"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_TEAM_ID = "team_id"
        const val EXTRA_TEAM_NAME = "team_name"
        const val EXTRA_TOKEN_TYPE = "token_type"
        const val EXTRA_SECONDARY_TOKEN = "secondary_token"
        const val EXTRA_TEAM_DOMAIN = "team_domain"
    }
}
