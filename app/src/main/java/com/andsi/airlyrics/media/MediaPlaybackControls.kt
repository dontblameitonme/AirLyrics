package com.andsi.airlyrics.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState

/** Transport actions a player currently advertises through its media session. */
data class MediaPlaybackCapabilities(
    val canPlayPause: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canSeek: Boolean
) {
    companion object {
        val None = MediaPlaybackCapabilities(
            canPlayPause = false,
            canSkipNext = false,
            canSkipPrevious = false,
            canSeek = false
        )

        /**
         * Some players publish a playback state without any action flags. Basic transport
         * commands are still worth offering then, but seeking stays disabled because a
         * rejected seek would make the in-app timeline jump away from the real position.
         */
        fun fromActions(actions: Long): MediaPlaybackCapabilities {
            if (actions == 0L) {
                return MediaPlaybackCapabilities(
                    canPlayPause = true,
                    canSkipNext = true,
                    canSkipPrevious = true,
                    canSeek = false
                )
            }
            fun has(flag: Long) = actions and flag != 0L
            return MediaPlaybackCapabilities(
                canPlayPause = has(PlaybackState.ACTION_PLAY_PAUSE) ||
                    has(PlaybackState.ACTION_PLAY) || has(PlaybackState.ACTION_PAUSE),
                canSkipNext = has(PlaybackState.ACTION_SKIP_TO_NEXT),
                canSkipPrevious = has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
                canSeek = has(PlaybackState.ACTION_SEEK_TO)
            )
        }
    }
}

/**
 * Sends transport commands to the player AirLyrics follows.
 *
 * The controller is resolved on every call so commands always target the session that is
 * active now, even after the player recreated its session (Spotify does this on account or
 * device switches).
 */
class MediaPlaybackControls(
    private val controllerProvider: () -> MediaController?
) {
    fun controller(): MediaController? = controllerProvider()

    fun capabilities(): MediaPlaybackCapabilities {
        val state = controller()?.playbackState ?: return MediaPlaybackCapabilities.None
        return MediaPlaybackCapabilities.fromActions(state.actions)
    }

    fun togglePlayPause(): Boolean = runCommand { controller ->
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipToNext(): Boolean = runCommand { it.transportControls.skipToNext() }

    fun skipToPrevious(): Boolean = runCommand { it.transportControls.skipToPrevious() }

    /** Seeks to [positionMs] in player time. Returns the clamped target, or null when rejected. */
    fun seekTo(positionMs: Long): Long? {
        val controller = controller() ?: return null
        if (!capabilities().canSeek) return null
        val durationMs = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val target = clampSeekPositionMs(positionMs, durationMs)
        val sent = runCatching { controller.transportControls.seekTo(target) }.isSuccess
        return target.takeIf { sent }
    }

    fun seekBy(deltaMs: Long): Long? {
        val state = controller()?.playbackState ?: return null
        return seekTo(CurrentMediaReader.estimatedPositionMs(state) + deltaMs)
    }

    private inline fun runCommand(command: (MediaController) -> Unit): Boolean {
        val controller = controller() ?: return false
        return runCatching { command(controller) }.isSuccess
    }

    companion object {
        /** Follows the same player selection rules as the floating lyrics service. */
        fun forSelectedPlayer(context: Context): MediaPlaybackControls {
            val appContext = context.applicationContext
            return MediaPlaybackControls {
                CurrentMediaReader.bestController(
                    CurrentMediaReader.getActiveControllers(appContext),
                    MediaSourceStore.getSelectedPackage(appContext)
                )
            }
        }

        internal fun clampSeekPositionMs(positionMs: Long, durationMs: Long): Long {
            val lowerBounded = positionMs.coerceAtLeast(0L)
            // Seeking exactly to the end makes several players skip immediately; stay just before it.
            return if (durationMs > 0L) lowerBounded.coerceAtMost((durationMs - 500L).coerceAtLeast(0L)) else lowerBounded
        }
    }
}

object MediaArtworkReader {
    fun read(metadata: MediaMetadata?): Bitmap? {
        metadata ?: return null
        return metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }
}
