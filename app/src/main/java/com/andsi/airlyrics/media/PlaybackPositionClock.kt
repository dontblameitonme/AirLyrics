package com.andsi.airlyrics.media

import android.media.session.PlaybackState
import android.os.SystemClock
import kotlin.math.abs

/**
 * Smooth playback position for frame-by-frame lyrics.
 *
 * Players such as Spotify only report a position when the state changes (play, pause, seek,
 * track change), so the position is extrapolated between reports. Two kinds of stale reports
 * are filtered out to keep the lyrics from jumping backwards:
 * - a slightly older position arriving while playback continues;
 * - the pre-seek position arriving after the user asked to seek.
 */
class PlaybackPositionClock(
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var basePositionMs = 0L
    private var baseTimeMs = 0L
    private var playing = false
    private var speed = 1f
    private var preSeekPositionMs: Long? = null
    private var preSeekWasPlaying = false
    private var pendingSeekRequestedAtMs = 0L
    private var hasPosition = false

    val isPlaying: Boolean get() = playing

    fun reset() {
        basePositionMs = 0L
        baseTimeMs = 0L
        playing = false
        speed = 1f
        preSeekPositionMs = null
        hasPosition = false
    }

    fun update(state: PlaybackState?) {
        if (state == null || state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return
        update(
            positionMs = state.position,
            updateTimeMs = state.lastPositionUpdateTime,
            isPlaying = state.state == PlaybackState.STATE_PLAYING,
            playbackSpeed = state.playbackSpeed
        )
    }

    fun update(positionMs: Long, updateTimeMs: Long, isPlaying: Boolean, playbackSpeed: Float) {
        val now = elapsedRealtimeMs()
        val incomingSpeed = playbackSpeed.takeIf { it > 0f } ?: 1f
        val reportedAt = updateTimeMs.takeIf { it in 1L..now } ?: now
        val incomingNowMs = if (isPlaying) {
            positionMs + ((now - reportedAt) * incomingSpeed).toLong()
        } else {
            positionMs
        }.coerceAtLeast(0L)

        preSeekPositionMs?.let { previous ->
            val sinceSeekMs = now - pendingSeekRequestedAtMs
            val oldTrajectoryMs = if (preSeekWasPlaying) previous + sinceSeekMs else previous
            if (sinceSeekMs < SEEK_SETTLE_MS && abs(incomingNowMs - oldTrajectoryMs) <= STALE_TOLERANCE_MS) {
                // Report still describes the position before the seek; keep the optimistic one.
                rebase(positionMs(), now, isPlaying, incomingSpeed)
                return
            }
            preSeekPositionMs = null
            // The seek has landed, so trust this report even if it is slightly behind.
            rebase(incomingNowMs, now, isPlaying, incomingSpeed)
            return
        }

        val estimatedNowMs = positionMs()
        val backtrackMs = estimatedNowMs - incomingNowMs
        val staleBacktrack = hasPosition && playing && isPlaying && backtrackMs in 1L..STALE_TOLERANCE_MS
        rebase(if (staleBacktrack) estimatedNowMs else incomingNowMs, now, isPlaying, incomingSpeed)
    }

    /** Records an accepted seek so the timeline moves immediately instead of waiting for the player. */
    fun onSeekRequested(targetMs: Long) {
        val now = elapsedRealtimeMs()
        preSeekPositionMs = positionMs()
        preSeekWasPlaying = playing
        pendingSeekRequestedAtMs = now
        rebase(targetMs, now, playing, speed)
    }

    fun positionMs(): Long {
        if (!hasPosition) return 0L
        if (!playing) return basePositionMs
        val elapsed = (elapsedRealtimeMs() - baseTimeMs).coerceAtLeast(0L)
        return basePositionMs + (elapsed * speed).toLong()
    }

    private fun rebase(positionMs: Long, now: Long, isPlaying: Boolean, playbackSpeed: Float) {
        basePositionMs = positionMs.coerceAtLeast(0L)
        baseTimeMs = now
        playing = isPlaying
        speed = playbackSpeed
        hasPosition = true
    }

    companion object {
        internal const val STALE_TOLERANCE_MS = 1_500L
        internal const val SEEK_SETTLE_MS = 2_000L
    }
}
