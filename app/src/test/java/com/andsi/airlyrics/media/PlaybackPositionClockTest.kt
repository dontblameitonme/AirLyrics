package com.andsi.airlyrics.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionClockTest {
    private var now = 10_000L
    private val clock = PlaybackPositionClock { now }

    @Test
    fun extrapolatesWhilePlayingAndHoldsWhilePaused() {
        clock.update(positionMs = 30_000L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        now += 2_500L
        assertEquals(32_500L, clock.positionMs())

        clock.update(positionMs = 32_500L, updateTimeMs = now, isPlaying = false, playbackSpeed = 1f)
        now += 5_000L
        assertEquals(32_500L, clock.positionMs())
    }

    @Test
    fun accountsForReportsTakenBeforeTheyArrive() {
        clock.update(positionMs = 1_000L, updateTimeMs = now - 400L, isPlaying = true, playbackSpeed = 1f)
        assertEquals(1_400L, clock.positionMs())
    }

    @Test
    fun ignoresSmallStaleBacktrackWhilePlaying() {
        clock.update(positionMs = 10_000L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        now += 1_000L
        clock.update(positionMs = 10_200L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        assertEquals(11_000L, clock.positionMs())
    }

    @Test
    fun acceptsLargeJumpsBackSuchAsRestartingTheSong() {
        clock.update(positionMs = 90_000L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        now += 100L
        clock.update(positionMs = 0L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        assertEquals(0L, clock.positionMs())
    }

    @Test
    fun seekMovesImmediatelyAndFiltersThePreSeekReport() {
        clock.update(positionMs = 20_000L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        clock.onSeekRequested(60_000L)
        now += 200L
        // Player still reports the old position once before applying the seek.
        clock.update(positionMs = 20_200L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        assertEquals(60_200L, clock.positionMs())

        now += 300L
        // Once the seek lands, later reports are trusted again (tiny backtracks still smoothed).
        clock.update(positionMs = 65_000L, updateTimeMs = now, isPlaying = true, playbackSpeed = 1f)
        assertEquals(65_000L, clock.positionMs())
    }

    @Test
    fun clampsSeekTargetsInsideTheTrack() {
        assertEquals(0L, MediaPlaybackControls.clampSeekPositionMs(-5_000L, 180_000L))
        assertEquals(179_500L, MediaPlaybackControls.clampSeekPositionMs(200_000L, 180_000L))
        assertEquals(42_000L, MediaPlaybackControls.clampSeekPositionMs(42_000L, 0L))
    }
}
