package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.SongIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NowPlayingLyricsStatus { LOADING, AVAILABLE, UNAVAILABLE }

/** Lyrics most recently resolved for the song that is playing now. */
data class NowPlayingLyrics(
    val song: SongIdentity,
    val status: NowPlayingLyricsStatus,
    val result: LyricsProviderResult? = null,
    val message: String? = null,
    val revision: Long = 0L
)

/**
 * Process-wide handoff of the current song's lyrics.
 *
 * The floating service and the in-app lyrics page both resolve lyrics for the same song. The
 * one that finishes first publishes here so the other can reuse the result instead of running
 * a second online lookup. Entries are memory-only; storage remains the source of truth.
 */
object NowPlayingLyricsHub {
    private val state = MutableStateFlow<NowPlayingLyrics?>(null)
    private val revisions = java.util.concurrent.atomic.AtomicLong()

    val current: StateFlow<NowPlayingLyrics?> = state.asStateFlow()

    fun entryFor(song: SongIdentity): NowPlayingLyrics? =
        state.value?.takeIf { it.song.isSameSong(song) }

    fun markLoading(song: SongIdentity) {
        // Never downgrade a result that is already showing for the same song.
        val existing = entryFor(song)
        if (existing?.status == NowPlayingLyricsStatus.AVAILABLE) return
        update(NowPlayingLyrics(song, NowPlayingLyricsStatus.LOADING))
    }

    fun publish(song: SongIdentity, result: LyricsProviderResult) {
        val status = if (result.hasUsableLyrics()) {
            NowPlayingLyricsStatus.AVAILABLE
        } else {
            NowPlayingLyricsStatus.UNAVAILABLE
        }
        update(NowPlayingLyrics(song, status, result = result))
    }

    fun markUnavailable(song: SongIdentity, message: String?) {
        update(NowPlayingLyrics(song, NowPlayingLyricsStatus.UNAVAILABLE, message = message))
    }

    /** Drops the cached entry so the next reader resolves the song again, e.g. after an edit. */
    fun invalidate(song: SongIdentity? = null) {
        if (song == null || entryFor(song) != null) state.value = null
    }

    private fun update(entry: NowPlayingLyrics) {
        state.value = entry.copy(revision = revisions.incrementAndGet())
    }
}
