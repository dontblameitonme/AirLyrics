package com.andsi.airlyrics.songinfo

import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal enum class WebSearchEngine(val id: String) {
    BING("bing"),
    GOOGLE("google"),
    TINYFISH("tinyfish"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String?): WebSearchEngine? = entries.firstOrNull { it.id == id }
    }
}

internal data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val site: String? = null
)

internal data class WebSearchConfig(
    val engine: WebSearchEngine,
    val resultCount: Int = 5,
    val googleApiKey: String = "",
    val googleCx: String = "",
    val tinyFishApiKey: String = "",
    /** URL template containing {query}. */
    val customUrl: String = "",
    val customHeaderName: String = "",
    val customHeaderValue: String = ""
)

internal class WebSearchException(message: String) : IOException(message)

/**
 * Optional web search used to ground AI answers. Bing is scraped from its public result page
 * (best effort, may be blocked); Google uses the Custom Search JSON API; TinyFish and custom
 * endpoints return JSON. Blocking; call from a background thread.
 */
internal object WebSearchClient {
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    fun search(config: WebSearchConfig, query: String): List<WebSearchResult> {
        val count = config.resultCount.coerceIn(1, 10)
        val q = encode(query)
        val results = when (config.engine) {
            WebSearchEngine.BING -> parseBingHtml(
                SongInfoHttp.getText(
                    "https://www.bing.com/search?q=$q&setlang=zh-Hans&count=${count + 2}",
                    mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Accept" to "text/html,application/xhtml+xml",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
                    )
                )
            )
            WebSearchEngine.GOOGLE -> {
                if (config.googleApiKey.isBlank() || config.googleCx.isBlank()) {
                    throw WebSearchException("Google needs an API key and a search engine ID (cx)")
                }
                parseGoogleJson(
                    SongInfoHttp.getText(
                        "https://www.googleapis.com/customsearch/v1?key=${encode(config.googleApiKey.trim())}" +
                            "&cx=${encode(config.googleCx.trim())}&num=$count&q=$q",
                        mapOf("Accept" to "application/json")
                    )
                )
            }
            WebSearchEngine.TINYFISH -> {
                if (config.tinyFishApiKey.isBlank()) throw WebSearchException("TinyFish needs an API key")
                parseGenericJson(
                    SongInfoHttp.getText(
                        "https://api.search.tinyfish.ai?query=$q&language=zh",
                        mapOf("X-API-Key" to config.tinyFishApiKey.trim(), "Accept" to "application/json")
                    )
                )
            }
            WebSearchEngine.CUSTOM -> {
                val template = config.customUrl.trim()
                if (!template.startsWith("http://") && !template.startsWith("https://")) {
                    throw WebSearchException("Custom search URL is not set")
                }
                val url = if (template.contains("{query}")) template.replace("{query}", q) else template + q
                val headers = buildMap {
                    put("Accept", "application/json")
                    if (config.customHeaderName.isNotBlank()) put(config.customHeaderName.trim(), config.customHeaderValue.trim())
                }
                parseGenericJson(SongInfoHttp.getText(url, headers))
            }
        }
        return results.distinctBy { it.url }.take(count)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Parses organic results (`li.b_algo`) from a Bing result page. */
    internal fun parseBingHtml(html: String): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        val blocks = html.split(Regex("<li[^>]*class=\"b_algo[^\"]*\"[^>]*>")).drop(1)
        for (raw in blocks) {
            val block = raw.substringBefore("</li>")
            val h2 = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: continue
            val anchor = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(h2) ?: continue
            val url = decodeBingUrl(decodeEntities(anchor.groupValues[1]))
            if (!url.startsWith("http")) continue
            val title = cleanHtml(anchor.groupValues[2])
            val snippetHtml = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                ?: Regex("<div class=\"b_caption[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                ?: ""
            val snippet = cleanHtml(snippetHtml)
            if (title.isNotBlank()) results += WebSearchResult(title, url, snippet, hostOf(url))
        }
        return results
    }

    /** Bing sometimes wraps targets as `bing.com/ck/a?...&u=a1<base64url>`; unwrap them. */
    internal fun decodeBingUrl(url: String): String {
        if (!url.contains("bing.com/ck/")) return url
        val encoded = Regex("[?&]u=a1([^&]+)").find(url)?.groupValues?.get(1) ?: return url
        return runCatching {
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrDefault(url)
    }

    internal fun parseGoogleJson(body: String): List<WebSearchResult> {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw WebSearchException(it.optString("message").ifBlank { "Google search error" }) }
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val link = item.optString("link")
            if (link.isBlank()) null else WebSearchResult(
                title = item.optString("title"),
                url = link,
                snippet = item.optString("snippet").replace('\n', ' ').trim(),
                site = item.optString("displayLink").ifBlank { hostOf(link) }
            )
        }
    }

    /**
     * Generic JSON search response: finds the first array of objects that carry a URL
     * (`url`/`link`/`href`) and reads title / snippet with common field names.
     */
    internal fun parseGenericJson(body: String): List<WebSearchResult> {
        val root: Any = body.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
        val array = findResultArray(root, depth = 0) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.firstString("url", "link", "href") ?: return@mapNotNull null
            WebSearchResult(
                title = item.firstString("title", "name") ?: url,
                url = url,
                snippet = (item.firstString("snippet", "content", "description", "body", "summary", "text") ?: "")
                    .replace(Regex("\\s+"), " ").trim().take(500),
                site = item.firstString("site_name", "siteName", "source", "displayLink") ?: hostOf(url)
            )
        }
    }

    private fun findResultArray(node: Any?, depth: Int): JSONArray? {
        if (depth > 4) return null
        when (node) {
            is JSONArray -> {
                val first = node.optJSONObject(0)
                if (first != null && first.firstString("url", "link", "href") != null) return node
                for (i in 0 until node.length()) findResultArray(node.opt(i), depth + 1)?.let { return it }
            }
            is JSONObject -> {
                // Prefer conventional keys first so e.g. "results" beats an unrelated "related" list.
                val preferred = listOf("results", "items", "data", "organic", "organic_results", "webPages", "value", "web")
                for (key in preferred) if (node.has(key)) findResultArray(node.opt(key), depth + 1)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in preferred) continue
                    findResultArray(node.opt(key), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun JSONObject.firstString(vararg names: String): String? =
        names.firstNotNullOfOrNull { name -> if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotEmpty() } else null }

    private fun cleanHtml(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()

    internal fun decodeEntities(text: String): String =
        Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
                else -> when (entity) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    "ensp", "emsp", "thinsp" -> " "
                    "middot" -> "·"
                    "hellip" -> "…"
                    "mdash" -> "—"
                    "ndash" -> "–"
                    else -> match.value
                }
            }
        }

    private fun hostOf(url: String): String? =
        Regex("^https?://([^/:?#]+)").find(url)?.groupValues?.get(1)?.removePrefix("www.")
}
