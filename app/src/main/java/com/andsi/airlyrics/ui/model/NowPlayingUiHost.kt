package com.andsi.airlyrics.ui.model

import android.graphics.Bitmap

internal data class NowPlayingTrackUi(
    val title: String,
    val artist: String,
    val album: String,
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
    /** Re-reads the per-song lyrics offset after the user changed it. */
    fun refreshLyricsOffset()
    fun close()
}

internal enum class SongCommentSortUi { HOT, LATEST }

internal data class SongCommentUi(
    val nickname: String,
    val avatarUrl: String?,
    val content: String,
    val likedCount: Long,
    val timeText: String,
    val location: String?,
    val repliedNickname: String?,
    val repliedContent: String?
)

internal data class SongCommentsPageUi(
    /** Title and artists of the matched NetEase track, shown so a wrong match is obvious. */
    val matchedSong: String,
    val comments: List<SongCommentUi>,
    val total: Long,
    val nextOffset: Int,
    val hasMore: Boolean
)

internal data class AiIntroSettingsUi(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val prompt: String,
    val defaultPrompt: String
)

/** Handle for a background request started by the page; cancel when the UI goes away. */
internal fun interface UiRequestHandle {
    fun cancel()
}

internal interface NowPlayingUiHost {
    fun openNowPlayingSession(listener: NowPlayingListener): NowPlayingSession

    /** Results are delivered on the main thread unless the handle was cancelled. */
    fun loadSongComments(
        track: NowPlayingTrackUi,
        sort: SongCommentSortUi,
        offset: Int,
        deliver: (Result<SongCommentsPageUi>) -> Unit
    ): UiRequestHandle

    fun aiIntroSettings(): AiIntroSettingsUi
    fun saveAiIntroSettings(settings: AiIntroSettingsUi)

    /** Streams the AI reply; [onDelta] and [onFinished] run on the main thread. */
    fun streamAiSongIntro(
        track: NowPlayingTrackUi,
        lyricsText: String,
        onDelta: (String) -> Unit,
        onFinished: (Throwable?) -> Unit
    ): UiRequestHandle

    fun nowPlayingTranslationVisible(): Boolean
    fun setNowPlayingTranslationVisible(visible: Boolean)
}
