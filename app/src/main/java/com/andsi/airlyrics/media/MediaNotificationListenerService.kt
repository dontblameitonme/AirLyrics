package com.andsi.airlyrics.media

import android.content.Context
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.i18n.LocalizedServiceContext

class MediaNotificationListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mediaSessionObserver: MediaSessionObserver
    private var isExplicitlyStopping = false

    private val rescanRunnable = Runnable {
        mediaSessionObserver.refresh()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalizedServiceContext.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaSessionObserver = MediaSessionObserver(
            context = this,
            handler = handler,
            listener = object : MediaSessionObserver.Listener {
                override fun onCurrentMediaChanged(media: CurrentMediaInfo) {
                    publishMediaInfo(media)
                }

                override fun onMediaSourceLost(packageName: String) {
                    publishMediaSourceLost(packageName)
                }

                override fun onObservationError(message: String, error: Throwable) {
                    if (error is SecurityException) {
                        Log.e(TAG, "Need notification access permission", error)
                    } else {
                        Log.e(TAG, message, error)
                    }
                }
            }
        )
    }

    override fun onListenerConnected() {
        isExplicitlyStopping = false
        super.onListenerConnected()
        connected = true
        if (BuildConfig.DEBUG) Log.d(TAG, "Notification listener connected")
        if (!NotificationListenerDemand.isNeeded()) {
            // Bound by the system without an in-app consumer (boot, process restart,
            // force-stop recovery). Release the binding so the process is not pinned.
            if (BuildConfig.DEBUG) Log.d(TAG, "No listener demand, unbinding")
            performStop()
            handler.postDelayed({
                if (!NotificationListenerDemand.isNeeded() && !connected) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, 800L)
            return
        }
        mediaSessionObserver.start()
    }

    override fun onListenerDisconnected() {
        connected = false
        cleanupMediaSessions()
        super.onListenerDisconnected()
        if (!isExplicitlyStopping && NotificationListenerDemand.isNeeded()) {
            requestNotificationListenerRebind()
        }
    }

    // No onTaskRemoved override: this service is only bound by system_server and never
    // started, so the framework does not deliver onTaskRemoved to it.

    fun performStop() {
        isExplicitlyStopping = true
        connected = false
        cleanupMediaSessions()
        runCatching {
            requestUnbind()
        }
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            connected = false
        }
        cleanupMediaSessions()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        scheduleMediaSessionRescan()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        scheduleMediaSessionRescan()
    }

    private fun scheduleMediaSessionRescan() {
        // Some players refresh sessions only after notification changes, so scan once after the burst.
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, MEDIA_SESSION_RESCAN_DELAY_MS)
    }

    private fun cleanupMediaSessions() {
        handler.removeCallbacks(rescanRunnable)
        if (::mediaSessionObserver.isInitialized) {
            mediaSessionObserver.stop()
        }
    }

    private fun requestNotificationListenerRebind() {
        val component = ComponentName(
            this,
            MediaNotificationListenerService::class.java
        )

        runCatching {
            requestRebind(component)
        }
    }

    private fun publishMediaSourceLost(packageName: String) {
        if (packageName.isBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "media source lost: source=$packageName")
        }

        CurrentMediaBroadcast.mediaSourceLostIntent(this, packageName)?.let(::sendBroadcast)
    }

    private fun publishMediaInfo(media: CurrentMediaInfo) {
        if (media.title.isBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "media: source=${media.sourcePackage} title=${media.title} artist=${media.artist} " +
                        "duration=${media.durationMs} position=${media.positionMs} playing=${media.isPlaying} " +
                        "sequence=${media.snapshotSequence}"
            )
        }

        sendBroadcast(CurrentMediaBroadcast.mediaUpdateIntent(this, media))
    }

    companion object {
        private const val TAG = "MediaNotificationListenerService"
        private const val MEDIA_SESSION_RESCAN_DELAY_MS = 200L

        @Volatile
        private var instance: MediaNotificationListenerService? = null

        @Volatile
        private var connected = false

        fun isConnected(): Boolean = instance != null && connected

        fun requestStop() {
            instance?.performStop()
        }
    }
}
