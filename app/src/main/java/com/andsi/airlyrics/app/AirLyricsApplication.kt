package com.andsi.airlyrics.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.media.NotificationListenerDemand

class AirLyricsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LanguageSettingsStore.initialize(this)
        AppNightMode.applyStoredMode(this)
        com.andsi.airlyrics.lyrics.storage.LyricsStorage.scheduleCacheMaintenance(this)
        registerActivityLifecycleCallbacks(UiVisibilityListenerDemand(this))
    }

    /** Holds notification listener demand while any activity of this process is started. */
    private class UiVisibilityListenerDemand(
        private val application: Application
    ) : ActivityLifecycleCallbacks {
        private var startedActivities = 0

        override fun onActivityStarted(activity: Activity) {
            if (startedActivities++ == 0) {
                NotificationListenerDemand.acquire(
                    application,
                    NotificationListenerDemand.Reason.UI_VISIBLE
                )
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedActivities == 0) return
            if (--startedActivities == 0) {
                NotificationListenerDemand.release(NotificationListenerDemand.Reason.UI_VISIBLE)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
