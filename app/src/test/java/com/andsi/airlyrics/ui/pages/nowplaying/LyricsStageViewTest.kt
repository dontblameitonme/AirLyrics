package com.andsi.airlyrics.ui.pages.nowplaying

import com.andsi.airlyrics.ui.model.NowPlayingLineUi
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsStageViewTest {
    private val lines = listOf(
        NowPlayingLineUi(1_000L, "a"),
        NowPlayingLineUi(5_000L, "b"),
        NowPlayingLineUi(9_000L, "c")
    )

    @Test
    fun activeLineIsTheLastLineThatStarted() {
        assertEquals(-1, LyricsStageView.activeLineIndex(lines, 500L))
        assertEquals(0, LyricsStageView.activeLineIndex(lines, 1_000L))
        assertEquals(1, LyricsStageView.activeLineIndex(lines, 8_999L))
        assertEquals(2, LyricsStageView.activeLineIndex(lines, 60_000L))
        assertEquals(-1, LyricsStageView.activeLineIndex(emptyList(), 1_000L))
    }
}
