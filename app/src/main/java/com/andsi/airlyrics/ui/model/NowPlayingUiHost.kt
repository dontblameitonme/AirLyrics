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

internal data class AiPromptUi(
    val id: String,
    val name: String,
    val template: String
)

internal enum class SearchEngineUi { BING, GOOGLE, TINYFISH, CUSTOM }

internal data class AiIntroSettingsUi(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val prompts: List<AiPromptUi>,
    val searchEngine: SearchEngineUi,
    val enabledEngines: Set<SearchEngineUi>,
    val resultCount: Int,
    val googleApiKey: String,
    val googleCx: String,
    val tinyFishApiKey: String,
    val customUrl: String,
    val customHeaderName: String,
    val customHeaderValue: String
)

internal data class AiSourceUi(
    val title: String,
    val url: String,
    val site: String?
)

/** Progress of one AI request, reported before and while the reply streams. */
internal sealed interface AiIntroProgressUi {
    data class Searching(val engine: SearchEngineUi) : AiIntroProgressUi
    data class Searched(val engine: SearchEngineUi, val sources: List<AiSourceUi>) : AiIntroProgressUi
    data class SearchFailed(val engine: SearchEngineUi, val message: String) : AiIntroProgressUi
    data object Generating : AiIntroProgressUi
}

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
    fun defaultAiPrompts(): List<AiPromptUi>
    fun aiSelectedPromptId(): String?
    fun setAiSelectedPromptId(id: String)
    fun aiWebSearchOn(): Boolean
    fun setAiWebSearchOn(on: Boolean)
    fun setDefaultSearchEngine(engine: SearchEngineUi)

    /** Optionally searches the web, then streams the reply; callbacks run on the main thread. */
    fun streamAiSongIntro(
        track: NowPlayingTrackUi,
        lyricsText: String,
        prompt: AiPromptUi,
        webSearch: Boolean,
        onProgress: (AiIntroProgressUi) -> Unit,
        onDelta: (String) -> Unit,
        onFinished: (Throwable?) -> Unit
    ): UiRequestHandle

    fun nowPlayingTranslationVisible(): Boolean
    fun setNowPlayingTranslationVisible(visible: Boolean)

    /** Lets the cover-art accent follow the current artwork. */
    fun onNowPlayingArtwork(artwork: Bitmap?)

    /** null = follow system. */
    fun themeDarkOverride(): Boolean?
    fun setThemeDarkOverride(dark: Boolean?)
}
