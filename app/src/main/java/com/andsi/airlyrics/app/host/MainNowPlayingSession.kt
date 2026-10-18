package com.andsi.airlyrics.app.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.model.displayText
import com.andsi.airlyrics.i18n.lyrics.localizedLyricsLookupMessage
import com.andsi.airlyrics.lyrics.LyricsChangedBroadcast
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.LyricsSearchCoordinator
import com.andsi.airlyrics.lyrics.NowPlayingLyrics
import com.andsi.airlyrics.lyrics.NowPlayingLyricsHub
import com.andsi.airlyrics.lyrics.NowPlayingLyricsStatus
import com.andsi.airlyrics.lyrics.hasUsableLyrics
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaArtworkReader
import com.andsi.airlyrics.media.MediaPlaybackCapabilities
import com.andsi.airlyrics.media.MediaPlaybackControls
import com.andsi.airlyrics.media.PlaybackPositionClock
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.model.NowPlayingLineUi
import com.andsi.airlyrics.ui.model.NowPlayingListener
import com.andsi.airlyrics.ui.model.NowPlayingLyricsUi
import com.andsi.airlyrics.ui.model.NowPlayingSession
import com.andsi.airlyrics.ui.model.NowPlayingTrackUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the in-app lyrics page to the followed player and to [NowPlayingLyricsHub].
 *
 * Lyrics resolved by the floating service are reused. When the service is not resolving the
 * current song (for example because the floating window is off), this session runs the same
 * repository lookup itself and publishes the result for the service to reuse later.
 */
internal class MainNowPlayingSession(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: NowPlayingListener,
    private val appName: (String) -> String
) : NowPlayingSession {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val controls = MediaPlaybackControls.forSelectedPlayer(this.context)
    private val clock = PlaybackPositionClock()

    private var observed: MediaController? = null
    private var song: SongIdentity? = null
    private var offsetMs = 0L
    private var lastTrack: NowPlayingTrackUi? = null
    private var lastLyricsRevision = -1L
    private var lookupJob: Job? = null
    private var lookupToken: LyricsLookupCancellationToken? = null
    private var closed = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshTrack()

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            clock.update(state)
            refreshTrack()
        }

        override fun onSessionDestroyed() = resolveController()
    }

    private val rescan = object : Runnable {
        override fun run() {
            if (closed) return
            resolveController()
            song?.let { current ->
                val latestOffset = LyricsOffsetStore.getOffsetMs(context, current)
                if (latestOffset != offsetMs) offsetMs = latestOffset
            }
            handler.postDelayed(this, RESCAN_INTERVAL_MS)
        }
    }

    private val lyricsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val change = LyricsChangedBroadcast.readChange(intent) ?: return
            val current = song ?: return
            if (change.target?.let { !current.isSameSong(it) } == true) return
            NowPlayingLyricsHub.invalidate(current)
            lastLyricsRevision = -1L
            onSongChanged(current)
        }
    }

    private val hubJob: Job = scope.launch {
        NowPlayingLyricsHub.current.collect { entry -> entry?.let(::deliverIfCurrent) }
    }

    init {
        ContextCompat.registerReceiver(
            this.context,
            lyricsChangedReceiver,
            LyricsChangedBroadcast.lyricsChangedFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        resolveController()
        handler.postDelayed(rescan, RESCAN_INTERVAL_MS)
    }

    override fun playbackPositionMs(): Long = clock.positionMs()

    override fun lyricsPositionMs(): Long = (clock.positionMs() + offsetMs).coerceAtLeast(0L)

    override fun togglePlayPause() {
        controls.togglePlayPause()
    }

    override fun skipToNext() {
        controls.skipToNext()
    }

    override fun skipToPrevious() {
        controls.skipToPrevious()
    }

    override fun seekToPlayback(positionMs: Long) {
        controls.seekTo(positionMs)?.let(clock::onSeekRequested)
    }

    override fun seekToLyricLine(lineStartMs: Long) {
        // Lyrics time = player time + offset, so subtract the offset to reach the player position.
        seekToPlayback(lineStartMs - offsetMs)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        observed?.let { runCatching { it.unregisterCallback(callback) } }
        observed = null
        cancelLookup()
        hubJob.cancel()
        runCatching { context.unregisterReceiver(lyricsChangedReceiver) }
    }

    private fun resolveController() {
        if (closed) return
        val controller = controls.controller()
        if (controller?.sessionToken != observed?.sessionToken) {
            observed?.let { runCatching { it.unregisterCallback(callback) } }
            observed = controller
            clock.reset()
            controller?.let {
                runCatching { it.registerCallback(callback, handler) }
                clock.update(it.playbackState)
            }
        }
        refreshTrack()
    }

    private fun refreshTrack() {
        if (closed) return
        val controller = observed
        val media = controller?.let(CurrentMediaReader::currentMediaFromController)
        if (controller == null || media == null || media.isEmpty) {
            if (lastTrack != null || song != null) {
                lastTrack = null
                song = null
                cancelLookup()
            }
            listener.onTrackChanged(null)
            return
        }

        val capabilities = controller.playbackState
            ?.let { MediaPlaybackCapabilities.fromActions(it.actions) }
            ?: MediaPlaybackCapabilities.None
        val track = NowPlayingTrackUi(
            title = media.title,
            artist = media.artist,
            sourceName = appName(media.sourcePackage),
            durationMs = media.durationMs,
            isPlaying = media.isPlaying,
            artwork = MediaArtworkReader.read(controller.metadata),
            canPlayPause = capabilities.canPlayPause,
            canSkipNext = capabilities.canSkipNext,
            canSkipPrevious = capabilities.canSkipPrevious,
            canSeek = capabilities.canSeek
        )
        if (track != lastTrack) {
            lastTrack = track
            listener.onTrackChanged(track)
        }

        val identity = media.toSongIdentity()
        if (song?.isSameSong(identity) != true) {
            song = identity
            offsetMs = LyricsOffsetStore.getOffsetMs(context, identity)
            lastLyricsRevision = -1L
            onSongChanged(identity)
        }
    }

    private fun onSongChanged(identity: SongIdentity) {
        cancelLookup()
        val existing = NowPlayingLyricsHub.entryFor(identity)
        if (existing != null) {
            deliverIfCurrent(existing)
            if (existing.status != NowPlayingLyricsStatus.LOADING) return
        } else {
            listener.onLyricsChanged(NowPlayingLyricsUi.Loading(context.getString(R.string.ui_loading)))
        }
        // Give the floating service a moment to claim the song before starting our own lookup.
        // If it already claimed it, only take over when its lookup appears to have stalled.
        val graceMs = if (existing?.status == NowPlayingLyricsStatus.LOADING) STALLED_LOOKUP_MS else SERVICE_GRACE_MS
        lookupJob = scope.launch {
            delay(graceMs)
            val entry = NowPlayingLyricsHub.entryFor(identity)
            if (entry == null || entry.status == NowPlayingLyricsStatus.LOADING && entry.revision == existing?.revision) {
                lookup(identity)
            }
        }
    }

    private suspend fun lookup(identity: SongIdentity) {
        if (LyricsSearchCoordinator.manualMode(identity) != null) return
        NowPlayingLyricsHub.markLoading(identity)
        val token = LyricsLookupCancellationToken(
            requestKey = "in-app|${identity.storageKey()}|${SystemClock.uptimeMillis()}",
            generation = 0L
        )
        lookupToken = token
        val result = withContext(Dispatchers.IO) {
            LyricsRepository.findLyrics(
                context = context,
                settings = LyricsSettingsStore.getSettings(context),
                title = identity.title,
                artist = identity.artist,
                album = identity.album,
                durationMs = identity.durationMs,
                cancellationToken = token,
                onInterimResult = { interim ->
                    handler.post {
                        if (!token.isCancellationRequested && song?.isSameSong(identity) == true) {
                            NowPlayingLyricsHub.publish(identity, interim)
                        }
                    }
                }
            )
        }
        if (token.isCancellationRequested || song?.isSameSong(identity) != true) return
        lookupToken = null
        val lyrics = result.getOrNull()?.takeIf { it.hasUsableLyrics() }
        if (lyrics != null) {
            NowPlayingLyricsHub.publish(identity, lyrics)
        } else {
            val error = result.exceptionOrNull() as? LyricsLookupException
            NowPlayingLyricsHub.markUnavailable(
                identity,
                error?.let { context.localizedLyricsLookupMessage(it) }
                    ?: context.getString(R.string.ui_lyrics_not_found)
            )
        }
    }

    private fun cancelLookup() {
        lookupJob?.cancel()
        lookupJob = null
        lookupToken?.cancel()
        lookupToken = null
    }

    private fun deliverIfCurrent(entry: NowPlayingLyrics) {
        if (closed) return
        val current = song ?: return
        if (!entry.song.isSameSong(current) || entry.revision == lastLyricsRevision) return
        lastLyricsRevision = entry.revision
        listener.onLyricsChanged(entry.toUi())
    }

    private fun NowPlayingLyrics.toUi(): NowPlayingLyricsUi = when (status) {
        NowPlayingLyricsStatus.LOADING -> NowPlayingLyricsUi.Loading(context.getString(R.string.ui_loading))
        NowPlayingLyricsStatus.UNAVAILABLE -> NowPlayingLyricsUi.Message(
            message ?: context.getString(R.string.ui_lyrics_not_found)
        )
        NowPlayingLyricsStatus.AVAILABLE -> {
            val payload = result
            when {
                payload == null -> NowPlayingLyricsUi.Message(context.getString(R.string.ui_lyrics_not_found))
                payload.access?.isExpired() == true ->
                    NowPlayingLyricsUi.Message(context.getString(R.string.ui_lyrics_expired))
                else -> {
                    val lines = LrcParser.parseWithTranslation(payload.plainLrc, payload.translatedLrc)
                        .filterNot { it.isMetadata }
                        .map { NowPlayingLineUi(it.timeMs, it.text, it.translation) }
                    if (lines.isEmpty()) {
                        NowPlayingLyricsUi.Message(context.getString(R.string.ui_parsed_lyrics_are_empty))
                    } else {
                        NowPlayingLyricsUi.Lines(lines, payload.access?.attribution?.displayText())
                    }
                }
            }
        }
    }

    private companion object {
        const val RESCAN_INTERVAL_MS = 2_000L
        const val SERVICE_GRACE_MS = 800L
        const val STALLED_LOOKUP_MS = 10_000L
    }
}
