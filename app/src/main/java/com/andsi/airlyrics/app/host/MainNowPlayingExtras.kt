package com.andsi.airlyrics.app.host

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.theme.TonalScheme
import com.andsi.airlyrics.settings.store.AiIntroSettings
import com.andsi.airlyrics.settings.store.AiPromptSetting
import com.andsi.airlyrics.settings.store.NowPlayingSettingsStore
import com.andsi.airlyrics.settings.store.WebSearchSettings
import com.andsi.airlyrics.songinfo.AiSongIntroClient
import com.andsi.airlyrics.songinfo.AiSongIntroConfig
import com.andsi.airlyrics.songinfo.AiSongIntroRequest
import com.andsi.airlyrics.songinfo.NeteaseComment
import com.andsi.airlyrics.songinfo.NeteaseCommentSort
import com.andsi.airlyrics.songinfo.NeteaseCommentsClient
import com.andsi.airlyrics.songinfo.NeteaseSong
import com.andsi.airlyrics.songinfo.WebSearchClient
import com.andsi.airlyrics.songinfo.WebSearchConfig
import com.andsi.airlyrics.songinfo.WebSearchEngine
import com.andsi.airlyrics.ui.model.AiIntroProgressUi
import com.andsi.airlyrics.ui.model.AiIntroSettingsUi
import com.andsi.airlyrics.ui.model.AiPromptUi
import com.andsi.airlyrics.ui.model.AiSourceUi
import com.andsi.airlyrics.ui.model.SearchEngineUi
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
                    val cacheKey = if (sort == SongCommentSortUi.HOT && offset == 0) song.id.toString() else null
                    cacheKey?.let { firstHotPageCache[it] }?.let { return@runCatching it }
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
                    ).also { result -> cacheKey?.let { firstHotPageCache[it] = result } }
                }
            }
            if (!cancelled.get()) deliver(result)
        }
        return UiRequestHandle {
            cancelled.set(true)
            job.cancel()
        }
    }

    fun defaultPrompts(): List<AiPromptUi> = PRESETS.map { (id, nameRes, templateRes) ->
        AiPromptUi(id, context.getString(nameRes), context.getString(templateRes))
    }

    fun aiSettings(): AiIntroSettingsUi {
        val ai = NowPlayingSettingsStore.getAiSettings(context)
        val search = NowPlayingSettingsStore.getWebSearchSettings(context)
        val prompts = NowPlayingSettingsStore.getPrompts(context)?.map { AiPromptUi(it.id, it.name, it.template) }
            ?: defaultPrompts()
        return AiIntroSettingsUi(
            baseUrl = ai.baseUrl,
            model = ai.model,
            apiKey = ai.apiKey,
            prompts = prompts,
            searchEngine = engineUi(search.engine),
            enabledEngines = search.enabledEngines.mapNotNull { WebSearchEngine.fromId(it)?.toUi() }.toSet(),
            resultCount = search.resultCount,
            googleApiKey = search.googleApiKey,
            googleCx = search.googleCx,
            tinyFishApiKey = search.tinyFishApiKey,
            customUrl = search.customUrl,
            customHeaderName = search.customHeaderName,
            customHeaderValue = search.customHeaderValue
        )
    }

    fun saveAiSettings(settings: AiIntroSettingsUi) {
        NowPlayingSettingsStore.setAiSettings(context, AiIntroSettings(settings.baseUrl, settings.model, settings.apiKey))
        // Keep following the localized presets until the user actually changes them.
        val prompts = settings.prompts.takeUnless { it == defaultPrompts() }
            ?.map { AiPromptSetting(it.id, it.name, it.template) }
        NowPlayingSettingsStore.setPrompts(context, prompts)
        NowPlayingSettingsStore.setWebSearchSettings(
            context,
            WebSearchSettings(
                engine = settings.searchEngine.toEngine().id,
                enabledEngines = settings.enabledEngines.map { it.toEngine().id }.toSet(),
                resultCount = settings.resultCount,
                googleApiKey = settings.googleApiKey,
                googleCx = settings.googleCx,
                tinyFishApiKey = settings.tinyFishApiKey,
                customUrl = settings.customUrl,
                customHeaderName = settings.customHeaderName,
                customHeaderValue = settings.customHeaderValue
            )
        )
    }

    fun selectedPromptId(): String? = NowPlayingSettingsStore.getSelectedPromptId(context)
    fun setSelectedPromptId(id: String) = NowPlayingSettingsStore.setSelectedPromptId(context, id)
    fun webSearchOn(): Boolean = NowPlayingSettingsStore.isAiWebSearchOn(context)
    fun setWebSearchOn(on: Boolean) = NowPlayingSettingsStore.setAiWebSearchOn(context, on)

    fun setDefaultSearchEngine(engine: SearchEngineUi) {
        val current = NowPlayingSettingsStore.getWebSearchSettings(context)
        NowPlayingSettingsStore.setWebSearchSettings(
            context,
            current.copy(engine = engine.toEngine().id, enabledEngines = current.enabledEngines + engine.toEngine().id)
        )
    }

    fun streamIntro(
        track: NowPlayingTrackUi,
        lyricsText: String,
        prompt: AiPromptUi,
        webSearch: Boolean,
        onProgress: (AiIntroProgressUi) -> Unit,
        onDelta: (String) -> Unit,
        onFinished: (Throwable?) -> Unit
    ): UiRequestHandle {
        val cancelled = AtomicBoolean(false)
        val settings = aiSettings()
        fun post(block: () -> Unit) = handler.post { if (!cancelled.get()) block() }
        val job = scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    var searchBlock: String? = null
                    if (webSearch) {
                        val engine = settings.searchEngine
                        post { onProgress(AiIntroProgressUi.Searching(engine)) }
                        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ") +
                            " " + context.getString(R.string.ui_ai_search_query_suffix)
                        val outcome = runCatching { WebSearchClient.search(searchConfig(settings), query.trim()) }
                        val results = outcome.getOrNull().orEmpty()
                        if (results.isEmpty()) {
                            val message = outcome.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                                ?: context.getString(R.string.ui_ai_search_no_results)
                            post { onProgress(AiIntroProgressUi.SearchFailed(engine, message)) }
                        } else {
                            val sources = results.map { AiSourceUi(it.title, it.url, it.site) }
                            post { onProgress(AiIntroProgressUi.Searched(engine, sources)) }
                            searchBlock = AiSongIntroClient.formatSearchResults(
                                context.getString(R.string.ui_ai_search_header),
                                results
                            )
                        }
                    }
                    if (cancelled.get()) return@runCatching
                    post { onProgress(AiIntroProgressUi.Generating) }
                    AiSongIntroClient.stream(
                        AiSongIntroConfig(settings.baseUrl, settings.model, settings.apiKey, prompt.template),
                        AiSongIntroRequest(track.title, track.artist, track.album, lyricsText, searchBlock),
                        cancelled
                    ) { delta -> post { onDelta(delta) } }
                }.exceptionOrNull()
            }
            // Post so the completion is ordered after every queued delta.
            post { onFinished(error) }
        }
        return UiRequestHandle {
            cancelled.set(true)
            job.cancel()
        }
    }

    private var lastArtworkFingerprint: Long? = null

    /** Extracts a theme seed from new artwork off the main thread; [onSeed] runs on the main thread. */
    fun coverSeed(artwork: Bitmap?, onSeed: (Int) -> Unit) {
        if (artwork == null || artwork.isRecycled) return
        // Metadata refreshes often hand over a new Bitmap for the same cover; compare samples.
        val fingerprint = fingerprint(artwork)
        if (fingerprint == lastArtworkFingerprint) return
        lastArtworkFingerprint = fingerprint
        scope.launch {
            val seed = withContext(Dispatchers.Default) {
                runCatching { if (artwork.isRecycled) null else TonalScheme.seedFromBitmap(artwork) }.getOrNull()
            } ?: return@launch
            if (lastArtworkFingerprint == fingerprint) onSeed(seed)
        }
    }

    private fun fingerprint(bitmap: Bitmap): Long = runCatching { samplePixels(bitmap) }
        .getOrElse { System.identityHashCode(bitmap).toLong() }

    private fun samplePixels(bitmap: Bitmap): Long {
        var hash = (bitmap.width.toLong() shl 32) xor bitmap.height.toLong()
        for (y in 1..4) for (x in 1..4) {
            val pixel = bitmap.getPixel((bitmap.width - 1) * x / 5, (bitmap.height - 1) * y / 5)
            hash = hash * 31 + pixel
        }
        return hash
    }

    private fun searchConfig(settings: AiIntroSettingsUi) = WebSearchConfig(
        engine = settings.searchEngine.toEngine(),
        resultCount = settings.resultCount,
        googleApiKey = settings.googleApiKey,
        googleCx = settings.googleCx,
        tinyFishApiKey = settings.tinyFishApiKey,
        customUrl = settings.customUrl,
        customHeaderName = settings.customHeaderName,
        customHeaderValue = settings.customHeaderValue
    )

    private fun engineUi(id: String): SearchEngineUi = WebSearchEngine.fromId(id)?.toUi() ?: SearchEngineUi.BING

    private fun WebSearchEngine.toUi(): SearchEngineUi = when (this) {
        WebSearchEngine.BING -> SearchEngineUi.BING
        WebSearchEngine.GOOGLE -> SearchEngineUi.GOOGLE
        WebSearchEngine.TINYFISH -> SearchEngineUi.TINYFISH
        WebSearchEngine.CUSTOM -> SearchEngineUi.CUSTOM
    }

    private fun SearchEngineUi.toEngine(): WebSearchEngine = when (this) {
        SearchEngineUi.BING -> WebSearchEngine.BING
        SearchEngineUi.GOOGLE -> WebSearchEngine.GOOGLE
        SearchEngineUi.TINYFISH -> WebSearchEngine.TINYFISH
        SearchEngineUi.CUSTOM -> WebSearchEngine.CUSTOM
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
        val firstHotPageCache = ConcurrentHashMap<String, SongCommentsPageUi>()
        val PRESETS = listOf(
            Triple("preset_intro", R.string.ui_ai_preset_intro_name, R.string.ui_ai_preset_intro),
            Triple("preset_meaning", R.string.ui_ai_preset_meaning_name, R.string.ui_ai_preset_meaning),
            Triple("preset_background", R.string.ui_ai_preset_background_name, R.string.ui_ai_preset_background),
            Triple("preset_music", R.string.ui_ai_preset_music_name, R.string.ui_ai_preset_music),
            Triple("preset_artist", R.string.ui_ai_preset_artist_name, R.string.ui_ai_preset_artist),
            Triple("preset_trivia", R.string.ui_ai_preset_trivia_name, R.string.ui_ai_preset_trivia),
            Triple("preset_similar", R.string.ui_ai_preset_similar_name, R.string.ui_ai_preset_similar),
            Triple("preset_translate", R.string.ui_ai_preset_translate_name, R.string.ui_ai_preset_translate)
        )
    }
}
