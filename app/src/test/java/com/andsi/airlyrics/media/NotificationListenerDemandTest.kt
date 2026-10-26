package com.andsi.airlyrics.media

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.NotificationListenerDemand.Reason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationListenerDemandTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var rebinds = 0
    private var releases = 0
    private var connected = false
    private var access = true

    @Before
    fun setUp() {
        NotificationListenerDemand.resetForTest()
        NotificationListenerDemand.rebindAction = { rebinds++ }
        NotificationListenerDemand.releaseAction = { releases++ }
        NotificationListenerDemand.isConnected = { connected }
        NotificationListenerDemand.hasAccess = { access }
    }

    @After
    fun tearDown() {
        NotificationListenerDemand.resetForTest()
    }

    @Test
    fun acquire_requestsRebindWhenGrantedAndDisconnected() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)

        assertTrue(NotificationListenerDemand.isNeeded())
        assertEquals(1, rebinds)
    }

    @Test
    fun acquire_skipsRebindWithoutAccessOrWhenAlreadyConnected() {
        access = false
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        access = true
        connected = true
        NotificationListenerDemand.acquire(context, Reason.FLOATING_SERVICE)

        assertEquals(0, rebinds)
    }

    @Test
    fun release_unbindsOnlyAfterDelay() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        NotificationListenerDemand.release(Reason.UI_VISIBLE)

        idleFor(NotificationListenerDemand.RELEASE_DELAY_MS - 1)
        assertEquals(0, releases)

        idleFor(1)
        assertEquals(1, releases)
        assertFalse(NotificationListenerDemand.isNeeded())
    }

    @Test
    fun reacquireWithinDelay_cancelsPendingUnbind() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        NotificationListenerDemand.release(Reason.UI_VISIBLE)
        idleFor(NotificationListenerDemand.RELEASE_DELAY_MS / 2)
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)

        idleFor(NotificationListenerDemand.RELEASE_DELAY_MS * 2)
        assertEquals(0, releases)
    }

    @Test
    fun releasingOneOfTwoReasons_keepsBinding() {
        NotificationListenerDemand.acquire(context, Reason.UI_VISIBLE)
        NotificationListenerDemand.acquire(context, Reason.FLOATING_SERVICE)
        NotificationListenerDemand.release(Reason.FLOATING_SERVICE, immediate = true)

        idleFor(NotificationListenerDemand.RELEASE_DELAY_MS * 2)
        assertEquals(0, releases)
        assertTrue(NotificationListenerDemand.isNeeded())
    }

    @Test
    fun immediateRelease_unbindsSynchronously() {
        NotificationListenerDemand.acquire(context, Reason.FLOATING_SERVICE)
        NotificationListenerDemand.release(Reason.FLOATING_SERVICE, immediate = true)

        assertEquals(1, releases)
    }

    @Test
    fun releasingUnheldReason_isNoOp() {
        NotificationListenerDemand.release(Reason.UI_VISIBLE, immediate = true)

        assertEquals(0, releases)
    }

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
