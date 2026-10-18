package com.andsi.airlyrics.songinfo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSongIntroClientTest {
    private val request = AiSongIntroRequest("晴天", "周杰伦", "叶惠美", "故事的小黄花")

    @Test
    fun rendersPlaceholders() {
        val prompt = AiSongIntroClient.renderPrompt("{artist}《{title}》[{album}] {lyrics} {unknown}", request)
        assertEquals("周杰伦《晴天》[叶惠美] 故事的小黄花 {unknown}", prompt)
    }

    @Test
    fun searchPlaceholderIsReplacedInPlace() {
        val prompt = AiSongIntroClient.renderPrompt("A {search} B", request.copy(search = "RESULTS"))
        assertEquals("A RESULTS B", prompt)
        assertEquals("A  B", AiSongIntroClient.renderPrompt("A {search} B", request))
    }

    @Test
    fun searchResultsAreAppendedWhenTemplateHasNoPlaceholder() {
        val prompt = AiSongIntroClient.renderPrompt("Intro {title}\n", request.copy(search = "RESULTS"))
        assertEquals("Intro 晴天\n\nRESULTS", prompt)
        assertEquals("Intro 晴天\n", AiSongIntroClient.renderPrompt("Intro {title}\n", request))
    }

    @Test
    fun formatsNumberedSearchResults() {
        val text = AiSongIntroClient.formatSearchResults(
            "Header:",
            listOf(
                WebSearchResult("First", "https://a.example/1", "Snippet one"),
                WebSearchResult("Second", "https://b.example/2", "")
            )
        )
        assertEquals("Header:\n\n[1] First\nhttps://a.example/1\nSnippet one\n\n[2] Second\nhttps://b.example/2", text)
    }

    @Test
    fun truncatesLongLyrics() {
        val prompt = AiSongIntroClient.renderPrompt("{lyrics}", request.copy(lyrics = "a".repeat(5_000)))
        assertTrue(prompt.length in 3_000..3_001)
    }

    @Test
    fun buildsChatCompletionsUrl() {
        assertEquals("https://api.openai.com/v1/chat/completions", AiSongIntroClient.chatCompletionsUrl("https://api.openai.com/v1/"))
        assertEquals("https://x.ai/v1/chat/completions", AiSongIntroClient.chatCompletionsUrl(" https://x.ai/v1/chat/completions "))
    }

    @Test
    fun buildsStreamingBody() {
        val body = JSONObject(AiSongIntroClient.buildBody(AiSongIntroConfig("u", " gpt-4o-mini ", "k", "介绍{title}"), request))
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("介绍晴天", message.getString("content"))
    }

    @Test
    fun parsesSseDeltas() {
        assertEquals("你好", AiSongIntroClient.parseSseLine("""data: {"choices":[{"delta":{"content":"你好"}}]}"""))
        assertNull(AiSongIntroClient.parseSseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(AiSongIntroClient.parseSseLine("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertNull(AiSongIntroClient.parseSseLine("data: [DONE]"))
        assertNull(AiSongIntroClient.parseSseLine(": keep-alive"))
        assertNull(AiSongIntroClient.parseSseLine(""))
    }

    @Test(expected = AiSongIntroException::class)
    fun sseErrorThrows() {
        AiSongIntroClient.parseSseLine("""data: {"error":{"message":"quota exceeded"}}""")
    }

    @Test
    fun parsesNonStreamingReplyAndErrors() {
        assertEquals("hi", AiSongIntroClient.parseCompleteMessage("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertEquals("Invalid key", AiSongIntroClient.parseErrorMessage("""{"error":{"message":"Invalid key"}}"""))
        assertEquals("bad", AiSongIntroClient.parseErrorMessage("""{"error":"bad"}"""))
        assertEquals("plain text", AiSongIntroClient.parseErrorMessage("plain text"))
        assertNull(AiSongIntroClient.parseErrorMessage(""))
    }
}
