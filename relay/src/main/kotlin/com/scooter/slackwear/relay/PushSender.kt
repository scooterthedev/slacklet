package com.scooter.slackwear.relay

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import org.slf4j.LoggerFactory
import java.io.FileInputStream

sealed interface PushOutcome {
    data object Accepted : PushOutcome
    data object Retryable : PushOutcome
    data object InvalidToken : PushOutcome
    data object PermanentFailure : PushOutcome
}

interface PushSender {
    val isReady: Boolean
    fun send(device: Device, payload: Map<String, String>): PushOutcome
}

class FcmPushSender internal constructor(private val messaging: FirebaseMessaging?) : PushSender {
    constructor(credentialsPath: String?) : this(initialize(credentialsPath))

    override val isReady: Boolean get() = messaging != null

    override fun send(device: Device, payload: Map<String, String>): PushOutcome {
        val messaging = messaging ?: return PushOutcome.Retryable
        return try {
            val message = Message.builder()
                .setToken(device.fcmToken)
                .putAllData(payload)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setTtl(60L * 60L * 1000L)
                        .build(),
                )
                .build()
            messaging.send(message)
            PushOutcome.Accepted
        } catch (e: FirebaseMessagingException) {
            outcomeFor(e.messagingErrorCode)
        } catch (_: IllegalArgumentException) {
            PushOutcome.PermanentFailure
        }
    }

    companion object {
        internal fun outcomeFor(code: MessagingErrorCode?): PushOutcome = when (code) {
            MessagingErrorCode.UNREGISTERED -> PushOutcome.InvalidToken
            MessagingErrorCode.INVALID_ARGUMENT,
            MessagingErrorCode.SENDER_ID_MISMATCH,
            MessagingErrorCode.THIRD_PARTY_AUTH_ERROR -> PushOutcome.PermanentFailure
            else -> PushOutcome.Retryable
        }

        private fun initialize(credentialsPath: String?): FirebaseMessaging? = runCatching {
            if (FirebaseApp.getApps().isEmpty()) {
                val credentials = if (credentialsPath != null) {
                    FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) }
                } else {
                    GoogleCredentials.getApplicationDefault()
                }
                FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials).build())
            }
            FirebaseMessaging.getInstance()
        }.onFailure {
            LoggerFactory.getLogger(FcmPushSender::class.java)
                .error("Firebase unavailable; delivery attempts will be retryable")
        }.getOrNull()
    }
}
