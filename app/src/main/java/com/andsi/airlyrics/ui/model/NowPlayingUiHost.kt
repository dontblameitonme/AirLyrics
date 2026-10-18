package com.andsi.airlyrics.ui.model

import android.graphics.Bitmap

internal data class NowPlayingTrackUi(
    val title: String,
    val artist: String,
    val sourceName: String,
    val durationMs: Long,
    val isPlaying: Boolean,
    val artwork: Bitmap?,
    val canPlayPause: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val canSeek: Boolean
)

/** One timed lyric line in lyrics time (player position plus the per-song offset). */
internal data class NowPlayingLineUi(
    val startMs: Long,
    val text: String,
    val translation: String? = null
)

internal sealed interface NowPlayingLyricsUi {
    data class Loading(val message: String) : NowPlayingLyricsUi
    data class Message(val message: String) : NowPlayingLyricsUi
    data class Lines(
        val lines: List<NowPlayingLineUi>,
        val attribution: CharSequence? = null
    ) : NowPlayingLyricsUi
}

internal interface NowPlayingListener {
    /** Null means no supported player is active. */
    fun onTrackChanged(track: NowPlayingTrackUi?)
    fun onLyricsChanged(lyrics: NowPlayingLyricsUi)
}

/** Live connection between the in-app lyrics page and the followed player. */
internal interface NowPlayingSession {
    fun playbackPositionMs(): Long
    fun lyricsPositionMs(): Long
    fun togglePlayPause()
    fun skipToNext()
    fun skipToPrevious()
    fun seekToPlayback(positionMs: Long)
    fun seekToLyricLine(lineStartMs: Long)
    fun close()
}

internal interface NowPlayingUiHost {
    fun openNowPlayingSession(listener: NowPlayingListener): NowPlayingSession
}
