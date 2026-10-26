package com.andsi.airlyrics.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.NotificationListenerDemand.Reason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaNotificationListenerServiceLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ServiceController<MediaNotificationListenerService>? = null
    private var rebinds = 0

    @Before
    fun setUp() {
        NotificationListenerDemand.resetForTest()
        NotificationListenerDemand.rebindAction = { rebinds++ }
        NotificationListenerDemand.hasAccess = { true }
        NotificationListenerDemand.releaseAction = {}
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        NotificationListenerDemand.resetForTest()
    }

    @Test
    fun connectWithoutDemand_unbindsImmediately() {
        val service = createService()

        service.onListenerConnected()

        assertFalse(MediaNotificationListenerService.isConnected())
    }

    @Test
    fun connectWithDemand_staysConnected() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        val service = createService()

        service.onListenerConnected()

        assertTrue(MediaNotificationListenerService.isConnected())
    }

    @Test
    fun disconnectAfterExplicitStop_doesNotRebind() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        val service = createService()
        service.onListenerConnected()
        rebinds = 0

        service.performStop()
        service.onListenerDisconnected()

        assertEquals(0, rebinds)
        assertFalse(MediaNotificationListenerService.isConnected())
    }

    @Test
    fun destroy_clearsConnectedState() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        val service = createService()
        service.onListenerConnected()

        controller?.destroy()
        controller = null

        assertFalse(MediaNotificationListenerService.isConnected())
    }

    private fun createService(): MediaNotificationListenerService {
        return Robolectric.buildService(MediaNotificationListenerService::class.java)
            .create()
            .also { controller = it }
            .get()
    }
}
