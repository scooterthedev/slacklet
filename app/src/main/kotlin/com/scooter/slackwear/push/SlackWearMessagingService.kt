package com.scooter.slackwear.push

import com.scooter.slackwear.SlackWearApplication
import com.scooter.slackwear.di.AppContainer
import com.scooter.slackwear.feature.notifications.NotificationPolicy
import com.scooter.slackwear.feature.notifications.PushNotifier
import com.scooter.slackwear.feature.notifications.PushPayload
import com.scooter.slackwear.feature.notifications.SlackMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SlackWearMessagingService : SlackMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val container: AppContainer
        get() = (application as SlackWearApplication).container

    override val notifier: PushNotifier
        get() = container.pushNotifier

    override val policy: NotificationPolicy
        get() = container.notificationPolicy

    override fun onTokenRotated(token: String) {
        scope.launch { container.pushRegistration.registerCurrent() }
    }

    override fun onPushDelivered(payload: PushPayload) {
        if (!(application as SlackWearApplication).isForeground) return
        scope.launch { container.activityRepository.refresh() }
    }
}
