package com.andsi.airlyrics.songinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCommentsClientTest {
    @Test
    fun parsesSearchResults() {
        val body = """
            {"result":{"songs":[
              {"id":186016,"name":"晴天","ar":[{"name":"周杰伦"}],"al":{"name":"叶惠美"},"dt":269000},
              {"id":0,"name":"broken"}
            ]},"code":200}
        """.trimIndent()
        val songs = NeteaseCommentsClient.parseSearch(body)
        assertEquals(1, songs.size)
        assertEquals(NeteaseSong(186016, "晴天", listOf("周杰伦"), "叶惠美", 269000), songs[0])
    }

    @Test
    fun parsesHotCommentsWithReplies() {
        val body = """
            {"code":200,"total":2,"hasMore":true,"hotComments":[
              {"commentId":1,"user":{"nickname":"小明","avatarUrl":"https://p1.music.126.net/a.jpg"},
               "content":"好听","likedCount":12345,"time":1700000000000,"timeStr":"2023-11-14",
               "ipLocation":{"location":"上海"},
               "beReplied":[{"content":"同感","user":{"nickname":"小红"}}]},
              {"commentId":2,"user":{"nickname":"B","avatarUrl":null},"content":"x","likedCount":0,"time":0,
               "ipLocation":{"location":""},"beReplied":[]}
            ]}
        """.trimIndent()
        val page = NeteaseCommentsClient.parseComments(body, NeteaseCommentSort.HOT)
        assertEquals(2L, page.total)
        assertTrue(page.hasMore)
        val first = page.comments[0]
        assertEquals("小明", first.nickname)
        assertEquals("上海", first.location)
        assertEquals("小红", first.repliedNickname)
        assertEquals("同感", first.repliedContent)
        assertEquals(12345L, first.likedCount)
        val second = page.comments[1]
        assertNull(second.avatarUrl)
        assertNull(second.location)
        assertNull(second.repliedContent)
    }

    @Test
    fun latestCommentsUseMoreFlag() {
        val body = """{"code":200,"total":0,"more":false,"comments":[]}"""
        val page = NeteaseCommentsClient.parseComments(body, NeteaseCommentSort.LATEST)
        assertTrue(page.comments.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test(expected = IllegalStateException::class)
    fun errorCodeThrows() {
        NeteaseCommentsClient.parseComments("""{"code":-460,"message":"Cheating"}""", NeteaseCommentSort.HOT)
    }

    @Test
    fun matcherPrefersOriginalOverCoverAndLive() {
        val original = NeteaseSong(1, "晴天", listOf("周杰伦"), "叶惠美", 269_000)
        val cover = NeteaseSong(2, "晴天 (Cover 周杰伦)", listOf("某翻唱"), "", 268_000)
        val live = NeteaseSong(3, "晴天 (Live)", listOf("周杰伦"), "", 300_000)
        val best = NeteaseSongMatcher.best(listOf(cover, live, original), "晴天", "周杰伦", 269_500)
        assertEquals(1L, best?.id)
    }

    @Test
    fun matcherHandlesMultipleArtistsAndSuffixes() {
        val song = NeteaseSong(7, "Something Just Like This", listOf("The Chainsmokers", "Coldplay"), "", 247_000)
        val score = NeteaseSongMatcher.score(song, "Something Just Like This - Remastered", "The Chainsmokers & Coldplay", 247_000)
        assertTrue(score >= 70)
    }

    @Test
    fun matcherRejectsCoversWhenOriginalIsMissing() {
        val covers = listOf(
            NeteaseSong(1, "晴天 (原唱 周杰伦)", listOf("RyaVocal"), "", 270_738),
            NeteaseSong(2, "晴天 (钢琴版) [原唱: 周杰伦]", listOf("纪钧瀚"), "", 238_098),
            NeteaseSong(3, "晴天", listOf("空匪"), "", 202_000)
        )
        assertNull(NeteaseSongMatcher.best(covers, "晴天", "周杰伦", 269_000))
    }

    @Test
    fun matcherUsesArtistAliases() {
        val song = NeteaseSong(5, "Lemon", listOf("米津玄師"), "", 256_000, artistAliases = listOf("ハチ", "米津玄师"))
        assertEquals(5L, NeteaseSongMatcher.best(listOf(song), "Lemon", "米津玄师", 255_500)?.id)
        // Romanized names are not in NetEase aliases; exact title and duration still match.
        assertEquals(5L, NeteaseSongMatcher.best(listOf(song), "Lemon", "Kenshi Yonezu", 255_500)?.id)
    }

    @Test
    fun matcherRejectsUnrelatedResults() {
        val unrelated = NeteaseSong(9, "完全不同", listOf("别人"), "", 100_000)
        assertNull(NeteaseSongMatcher.best(listOf(unrelated), "晴天", "周杰伦", 269_000))
    }
}
