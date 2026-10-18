package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.SongIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingLyricsHubTest {
    private val song = SongIdentity("Song", "Artist", "Album", 200_000L)
    private val result = LyricsProviderResult("local", "local", "[00:01.00]hello")

    @After
    fun tearDown() = NowPlayingLyricsHub.invalidate()

    @Test
    fun publishedLyricsAreVisibleOnlyForTheSameSong() {
        NowPlayingLyricsHub.publish(song, result)
        assertEquals(NowPlayingLyricsStatus.AVAILABLE, NowPlayingLyricsHub.entryFor(song)?.status)
        assertNull(NowPlayingLyricsHub.entryFor(SongIdentity("Other", "Artist", "", 200_000L)))
    }

    @Test
    fun loadingNeverReplacesAvailableLyricsForTheSameSong() {
        NowPlayingLyricsHub.publish(song, result)
        NowPlayingLyricsHub.markLoading(song)
        assertEquals(result, NowPlayingLyricsHub.entryFor(song)?.result)
    }

    @Test
    fun blankPayloadIsReportedAsUnavailable() {
        NowPlayingLyricsHub.publish(song, LyricsProviderResult("local", "local", ""))
        assertEquals(NowPlayingLyricsStatus.UNAVAILABLE, NowPlayingLyricsHub.entryFor(song)?.status)
    }

    @Test
    fun invalidateDropsTheEntry() {
        NowPlayingLyricsHub.publish(song, result)
        NowPlayingLyricsHub.invalidate(song)
        assertNull(NowPlayingLyricsHub.entryFor(song))
    }
}
