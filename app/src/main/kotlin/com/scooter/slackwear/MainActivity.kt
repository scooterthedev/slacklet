package com.scooter.slackwear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.scooter.slackwear.navigation.SlackWearApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {

        const val EXTRA_CONVERSATION_ID = "conversationId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as SlackWearApplication).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.pushRegistration.whileForeground()
            }
        }
        setContent {
            SlackWearApp(
                container = container,
                initialConversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID),
            )
        }
    }
}
