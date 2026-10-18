package com.andsi.airlyrics.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackCapabilitiesTest {
    @Test
    fun readsAdvertisedActions() {
        val capabilities = MediaPlaybackCapabilities.fromActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO
        )
        assertEquals(MediaPlaybackCapabilities(true, true, false, true), capabilities)
    }

    @Test
    fun missingActionFlagsKeepBasicControlsButDisableSeeking() {
        assertEquals(
            MediaPlaybackCapabilities(true, true, true, false),
            MediaPlaybackCapabilities.fromActions(0L)
        )
    }
}
