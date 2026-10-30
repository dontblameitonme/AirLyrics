package com.andsi.airlyrics.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Tracks which in-process consumers currently need the notification listener binding.
 *
 * The listener is bound by system_server, so the app cannot rely on onTaskRemoved for it.
 * Instead the binding follows demand: it is requested while the UI is visible or the floating
 * service is alive, and released (via requestUnbind) shortly after the last consumer leaves.
 * Any binding initiated by the system without demand (boot, process restart, force-stop
 * recovery) is released by the listener itself in onListenerConnected.
 *
 * Main thread only.
 */
internal object NotificationListenerDemand {
    enum class Reason { UI_VISIBLE, FLOATING_SERVICE }

    internal const val RELEASE_DELAY_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private val reasons = mutableSetOf<Reason>()

    internal var rebindAction: (Context) -> Unit = ::requestSystemRebind
    internal var releaseAction: () -> Unit = { MediaNotificationListenerService.requestStop() }
    internal var isConnected: () -> Boolean = { MediaNotificationListenerService.isConnected() }
    internal var hasAccess: (Context) -> Boolean = ::hasListenerAccess

    private val releaseRunnable = Runnable {
        if (reasons.isEmpty()) releaseAction()
    }

    fun isNeeded(): Boolean = reasons.isNotEmpty()

    fun acquire(context: Context, reason: Reason) {
        handler.removeCallbacks(releaseRunnable)
        reasons.add(reason)
        requestRebindIfNeeded(context)
    }

    fun release(reason: Reason, immediate: Boolean = false) {
        if (!reasons.remove(reason)) return
        if (reasons.isNotEmpty()) return
        handler.removeCallbacks(releaseRunnable)
        if (immediate) {
            releaseRunnable.run()
        } else {
            handler.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
        }
    }

    fun releaseAllImmediate() {
        handler.removeCallbacks(releaseRunnable)
        reasons.clear()
        releaseAction()
    }

    fun requestRebindIfNeeded(context: Context) {
        if (!isNeeded()) return
        if (isConnected()) return
        val appContext = context.applicationContext ?: context
        if (!hasAccess(appContext)) return
        rebindAction(appContext)
    }

    private fun hasListenerAccess(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    private fun requestSystemRebind(context: Context) {
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context, MediaNotificationListenerService::class.java)
            )
        }
    }

    internal fun resetForTest() {
        handler.removeCallbacks(releaseRunnable)
        reasons.clear()
        rebindAction = ::requestSystemRebind
        releaseAction = { MediaNotificationListenerService.requestStop() }
        isConnected = { MediaNotificationListenerService.isConnected() }
        hasAccess = ::hasListenerAccess
    }
}
