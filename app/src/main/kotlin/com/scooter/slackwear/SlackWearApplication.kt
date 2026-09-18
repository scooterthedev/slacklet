package com.scooter.slackwear

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.scooter.slackwear.core.data.Emoji
import com.scooter.slackwear.core.designsystem.image.SlackImageLoader
import com.scooter.slackwear.di.AppContainer
import com.scooter.slackwear.feature.notifications.SlackNotifications
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SlackWearApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val startedActivities = AtomicInteger()

    val isForeground: Boolean get() = startedActivities.get() > 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities.incrementAndGet() }
            override fun onActivityStopped(activity: Activity) { startedActivities.decrementAndGet() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        applicationScope.launch { Emoji.initialize(this@SlackWearApplication) }

        SlackNotifications.createChannels(this)

        applicationScope.launch {
            container.pushRegistration.status.collect { status ->
                Log.i("PushRegistration", status.name)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext) = SlackImageLoader.create(this)
}
