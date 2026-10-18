package com.andsi.airlyrics.songinfo

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal data class AiSongIntroConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val promptTemplate: String
)

internal data class AiSongIntroRequest(
    val title: String,
    val artist: String,
    val album: String,
    val lyrics: String,
    /** Pre-formatted web search results, or null when search is off. */
    val search: String? = null
)

internal class AiSongIntroException(message: String) : IOException(message)

/**
 * Calls any OpenAI-compatible `/chat/completions` endpoint and streams the reply.
 * Blocking; run on a background thread and cancel through [cancelled].
 */
internal object AiSongIntroClient {
    private const val MAX_LYRICS_CHARS = 3_000
    private const val STREAM_READ_TIMEOUT_MS = 60_000

    fun stream(
        config: AiSongIntroConfig,
        request: AiSongIntroRequest,
        cancelled: AtomicBoolean,
        onDelta: (String) -> Unit
    ) {
        if (config.apiKey.isBlank()) throw AiSongIntroException("API key is empty")
        val connection = SongInfoHttp.open(
            chatCompletionsUrl(config.baseUrl),
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "text/event-stream, application/json",
                "Authorization" to "Bearer ${config.apiKey.trim()}"
            ),
            readTimeoutMs = STREAM_READ_TIMEOUT_MS
        )
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            val body = buildBody(config, request)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw AiSongIntroException("HTTP $code" + (parseErrorMessage(error)?.let { ": $it" } ?: ""))
            }
            val contentType = connection.contentType.orEmpty()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (!contentType.contains("event-stream")) {
                    // Some compatible servers ignore stream=true and answer with one JSON body.
                    val text = reader.readText()
                    if (text.trimStart().startsWith("data:")) {
                        text.lineSequence().forEach { line -> parseSseLine(line)?.let(onDelta) }
                    } else {
                        parseCompleteMessage(text)?.let(onDelta)
                            ?: throw AiSongIntroException(parseErrorMessage(text) ?: "Empty response")
                    }
                    return
                }
                while (!cancelled.get()) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == "data: [DONE]") break
                    parseSseLine(line)?.let(onDelta)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun chatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    internal fun buildBody(config: AiSongIntroConfig, request: AiSongIntroRequest): String =
        JSONObject()
            .put("model", config.model.trim())
            .put("stream", true)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", renderPrompt(config.promptTemplate, request))
                )
            )
            .toString()

    /**
     * Replaces {title}, {artist}, {album}, {lyrics} and {search}; unknown braces are left
     * untouched. Search results are appended when the template has no {search} placeholder.
     */
    internal fun renderPrompt(template: String, request: AiSongIntroRequest): String {
        val lyrics = request.lyrics.trim().let {
            if (it.length > MAX_LYRICS_CHARS) it.take(MAX_LYRICS_CHARS) + "…" else it
        }
        val search = request.search?.trim().orEmpty()
        val rendered = template
            .replace("{title}", request.title.trim())
            .replace("{artist}", request.artist.trim())
            .replace("{album}", request.album.trim())
            .replace("{lyrics}", lyrics)
        return when {
            template.contains("{search}") -> rendered.replace("{search}", search)
            search.isNotEmpty() -> rendered.trimEnd() + "\n\n" + search
            else -> rendered
        }
    }

    /** Numbered result list the model can cite as [1], [2]…; [header] explains how to use it. */
    internal fun formatSearchResults(header: String, results: List<WebSearchResult>): String = buildString {
        append(header.trim())
        results.forEachIndexed { index, result ->
            append("\n\n[").append(index + 1).append("] ").append(result.title.trim())
            append("\n").append(result.url)
            if (result.snippet.isNotBlank()) append("\n").append(result.snippet.trim())
        }
    }

    /** Extracts the text delta from one Server-Sent Events line, or null. */
    internal fun parseSseLine(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        json.optJSONObject("error")?.let { throw AiSongIntroException(it.optString("message").ifBlank { payload }) }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        return delta?.optStringOrNull("content")?.takeIf { it.isNotEmpty() }
    }

    internal fun parseCompleteMessage(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optStringOrNull("content")?.takeIf { it.isNotBlank() }
    }

    internal fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(200)
        val error = json.opt("error")
        return when (error) {
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
            is String -> error.takeIf { it.isNotBlank() }
            else -> json.optString("message").takeIf { it.isNotBlank() }
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)
}
