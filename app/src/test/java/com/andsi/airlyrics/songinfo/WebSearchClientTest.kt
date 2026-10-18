package com.andsi.airlyrics.songinfo

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientTest {
    @Test
    fun parsesBingOrganicResults() {
        val target = "https://music.example.com/song?id=1&x=2"
        val wrapped = "https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray()) + "&amp;ntb=1"
        val html = """
            <ol id="b_results">
            <li class="b_algo" data-id="1"><div class="b_tpcn"></div><h2><a href="$wrapped" h="ID=1">Song <strong>Title</strong> &amp; Story</a></h2>
              <div class="b_caption"><p class="b_lineclamp2">The &quot;story&quot; behind&nbsp;the song&#39;s lyrics &#x2014; ok</p></div></li>
            <li class="b_algo"><h2><a href="https://www.wiki.example.org/Artist">Artist - Wiki</a></h2><div class="b_caption"><p>Bio</p></div></li>
            <li class="b_ad"><h2><a href="https://ads.example">Ad</a></h2></li>
            </ol>
        """.trimIndent()
        val results = WebSearchClient.parseBingHtml(html)
        assertEquals(2, results.size)
        assertEquals("Song Title & Story", results[0].title)
        assertEquals(target, results[0].url)
        assertEquals("The \"story\" behind the song's lyrics \u2014 ok", results[0].snippet)
        assertEquals("music.example.com", results[0].site)
        assertEquals("https://www.wiki.example.org/Artist", results[1].url)
        assertEquals("wiki.example.org", results[1].site)
    }

    @Test
    fun bingPageWithoutResultsYieldsEmptyList() {
        assertTrue(WebSearchClient.parseBingHtml("<html><body>captcha</body></html>").isEmpty())
    }

    @Test
    fun leavesPlainUrlsUntouched() {
        assertEquals("https://a.example/x", WebSearchClient.decodeBingUrl("https://a.example/x"))
    }

    @Test
    fun parsesGoogleCustomSearch() {
        val body = """{"items":[{"title":"T1","link":"https://a.example/1","snippet":"S\n1","displayLink":"a.example"},{"title":"No link"}]}"""
        val results = WebSearchClient.parseGoogleJson(body)
        assertEquals(1, results.size)
        assertEquals("S 1", results[0].snippet)
        assertEquals("a.example", results[0].site)
    }

    @Test(expected = WebSearchException::class)
    fun googleErrorThrows() {
        WebSearchClient.parseGoogleJson("""{"error":{"code":403,"message":"API key not valid"}}""")
    }

    @Test
    fun parsesTinyFishStyleResults() {
        val body = """{"query":"q","results":[{"position":1,"site_name":"Example","title":"Hello","url":"https://ex.example/a","snippet":"World"}],"total_results":1}"""
        val results = WebSearchClient.parseGenericJson(body)
        assertEquals(listOf(WebSearchResult("Hello", "https://ex.example/a", "World", "Example")), results)
    }

    @Test
    fun genericParserFindsNestedArraysAndAlternateFieldNames() {
        val body = """{"meta":{"related":["x"]},"web":{"value":[{"name":"N","link":"https://n.example/p","description":"  D   d "}]}}"""
        val results = WebSearchClient.parseGenericJson(body)
        assertEquals(1, results.size)
        assertEquals("N", results[0].title)
        assertEquals("https://n.example/p", results[0].url)
        assertEquals("D d", results[0].snippet)
        assertEquals("n.example", results[0].site)
    }

    @Test
    fun genericParserAcceptsTopLevelArray() {
        val results = WebSearchClient.parseGenericJson("""[{"title":"A","href":"https://a.example","body":"B"}]""")
        assertEquals("B", results.single().snippet)
    }

    @Test
    fun decodesEntities() {
        assertEquals("a & b < c \u00e9 \u4e2d", WebSearchClient.decodeEntities("a &amp; b &lt; c &#233; &#x4e2d;"))
    }
}
