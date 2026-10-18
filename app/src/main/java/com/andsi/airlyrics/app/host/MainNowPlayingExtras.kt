package com.andsi.airlyrics.app.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.AiIntroSettings
import com.andsi.airlyrics.settings.store.NowPlayingSettingsStore
import com.andsi.airlyrics.songinfo.AiSongIntroClient
import com.andsi.airlyrics.songinfo.AiSongIntroConfig
import com.andsi.airlyrics.songinfo.AiSongIntroRequest
import com.andsi.airlyrics.songinfo.NeteaseComment
import com.andsi.airlyrics.songinfo.NeteaseCommentSort
import com.andsi.airlyrics.songinfo.NeteaseCommentsClient
import com.andsi.airlyrics.songinfo.NeteaseSong
import com.andsi.airlyrics.ui.model.AiIntroSettingsUi
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import com.andsi.airlyrics.ui.model.SongCommentSortUi
import com.andsi.airlyrics.ui.model.SongCommentUi
import com.andsi.airlyrics.ui.model.SongCommentsPageUi
import com.andsi.airlyrics.ui.model.UiRequestHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-layer backing for the in-app lyrics page's comments, AI intro and page preferences. */
internal class MainNowPlayingExtras(
    context: Context,
    private val scope: CoroutineScope
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    fun loadComments(
        track: NowPlayingTrackUi,
        sort: SongCommentSortUi,
        offset: Int,
        deliver: (Result<SongCommentsPageUi>) -> Unit
    ): UiRequestHandle {
        val cancelled = AtomicBoolean(false)
        val job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val song = songFor(track)
                        ?: throw NoSuchElementException(context.getString(R.string.ui_comments_song_not_found))
                    val page = NeteaseCommentsClient.loadComments(
                        song.id,
                        if (sort == SongCommentSortUi.HOT) NeteaseCommentSort.HOT else NeteaseCommentSort.LATEST,
                        offset,
                        PAGE_SIZE
                    )
                    SongCommentsPageUi(
                        matchedSong = song.name + song.artists.takeIf { it.isNotEmpty() }?.joinToString(" / ", prefix = " - ").orEmpty(),
                        comments = page.comments.map { it.toUi() },
                        total = page.total,
                        nextOffset = offset + page.comments.size,
                        hasMore = page.hasMore
                    )
                }
            }
            if (!cancelled.get()) deliver(result)
        }
        return UiRequestHandle {
            cancelled.set(true)
            job.cancel()
        }
    }

    fun aiSettings(): AiIntroSettingsUi {
        val defaultPrompt = context.getString(R.string.ui_ai_default_prompt)
        val stored = NowPlayingSettingsStore.getAiSettings(context, defaultPrompt)
        return AiIntroSettingsUi(stored.baseUrl, stored.model, stored.apiKey, stored.prompt, defaultPrompt)
    }

    fun saveAiSettings(settings: AiIntroSettingsUi) {
        NowPlayingSettingsStore.setAiSettings(
            context,
            AiIntroSettings(settings.baseUrl, settings.model, settings.apiKey, settings.prompt),
            context.getString(R.string.ui_ai_default_prompt)
        )
    }

    fun streamIntro(
        track: NowPlayingTrackUi,
        lyricsText: String,
        onDelta: (String) -> Unit,
        onFinished: (Throwable?) -> Unit
    ): UiRequestHandle {
        val cancelled = AtomicBoolean(false)
        val settings = aiSettings()
        val job = scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    AiSongIntroClient.stream(
                        AiSongIntroConfig(settings.baseUrl, settings.model, settings.apiKey, settings.prompt),
                        AiSongIntroRequest(track.title, track.artist, track.album, lyricsText),
                        cancelled
                    ) { delta -> handler.post { if (!cancelled.get()) onDelta(delta) } }
                }.exceptionOrNull()
            }
            // Post so the completion is ordered after every queued delta.
            handler.post { if (!cancelled.get()) onFinished(error) }
        }
        return UiRequestHandle {
            cancelled.set(true)
            job.cancel()
        }
    }

    fun translationVisible(): Boolean = NowPlayingSettingsStore.isTranslationVisible(context)

    fun setTranslationVisible(visible: Boolean) = NowPlayingSettingsStore.setTranslationVisible(context, visible)

    private fun songFor(track: NowPlayingTrackUi): NeteaseSong? {
        val key = listOf(track.title, track.artist, (track.durationMs / 1000L).toString()).joinToString("\u0000")
        songCache[key]?.let { return it }
        return NeteaseCommentsClient.findSong(track.title, track.artist, track.durationMs)?.also { songCache[key] = it }
    }

    private fun NeteaseComment.toUi() = SongCommentUi(
        nickname = nickname,
        avatarUrl = avatarUrl,
        content = content,
        likedCount = likedCount,
        timeText = timeText ?: if (timeMs > 0L) {
            DateUtils.getRelativeTimeSpanString(timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } else "",
        location = location,
        repliedNickname = repliedNickname,
        repliedContent = repliedContent
    )

    private companion object {
        const val PAGE_SIZE = 20
        val songCache = ConcurrentHashMap<String, NeteaseSong>()
    }
}
