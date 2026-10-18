package com.andsi.airlyrics.songinfo

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

internal data class NeteaseSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    /** Alternative artist names (aliases, translations) used only for matching. */
    val artistAliases: List<String> = emptyList()
)

internal enum class NeteaseCommentSort { HOT, LATEST }

internal data class NeteaseComment(
    val id: Long,
    val nickname: String,
    val avatarUrl: String?,
    val content: String,
    val likedCount: Long,
    val timeMs: Long,
    val timeText: String?,
    val location: String?,
    val repliedNickname: String?,
    val repliedContent: String?
)

internal data class NeteaseCommentPage(
    val comments: List<NeteaseComment>,
    val total: Long,
    val hasMore: Boolean
)

/**
 * Finds the NetEase Cloud Music track that matches the playing song and reads its public
 * comments. Uses the unauthenticated web endpoints; all calls are blocking.
 */
internal object NeteaseCommentsClient {
    private const val BASE = "https://music.163.com"
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        "Referer" to "$BASE/",
        "Accept" to "application/json"
    )

    fun findSong(title: String, artist: String, durationMs: Long): NeteaseSong? {
        val keyword = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (keyword.isBlank()) return null
        val url = "$BASE/api/cloudsearch/pc?s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&limit=10&offset=0"
        val songs = parseSearch(SongInfoHttp.getText(url, headers))
        return NeteaseSongMatcher.best(songs, title, artist, durationMs)
    }

    fun loadComments(songId: Long, sort: NeteaseCommentSort, offset: Int, limit: Int = 20): NeteaseCommentPage {
        val path = when (sort) {
            NeteaseCommentSort.HOT -> "hotcomments"
            NeteaseCommentSort.LATEST -> "comments"
        }
        val url = "$BASE/api/v1/resource/$path/R_SO_4_$songId?limit=$limit&offset=$offset"
        return parseComments(SongInfoHttp.getText(url, headers), sort)
    }

    internal fun parseSearch(body: String): List<NeteaseSong> {
        val root = JSONObject(body)
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { index ->
            val item = songs.optJSONObject(index) ?: return@mapNotNull null
            val artists = item.optJSONArray("ar")
            NeteaseSong(
                id = item.optLong("id"),
                name = item.optString("name"),
                artists = artists?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") } }
                    .orEmpty(),
                album = item.optJSONObject("al")?.optString("name").orEmpty(),
                durationMs = item.optLong("dt"),
                artistAliases = artists?.let { array ->
                    (0 until array.length()).flatMap { index ->
                        val artist = array.optJSONObject(index) ?: return@flatMap emptyList()
                        listOf("alia", "alias", "tns").flatMap { key ->
                            artist.optJSONArray(key)?.let { names -> (0 until names.length()).map { names.optString(it) } }.orEmpty()
                        }
                    }.filter { it.isNotBlank() }.distinct()
                }.orEmpty()
            ).takeIf { it.id > 0L }
        }
    }

    internal fun parseComments(body: String, sort: NeteaseCommentSort): NeteaseCommentPage {
        val root = JSONObject(body)
        val code = root.optInt("code", 200)
        if (code != 200) throw IllegalStateException(root.optString("message").ifBlank { "code $code" })
        val key = if (sort == NeteaseCommentSort.HOT) "hotComments" else "comments"
        val array = root.optJSONArray(key)
        val comments = if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val user = item.optJSONObject("user")
            val replied = item.optJSONArray("beReplied")?.optJSONObject(0)
            NeteaseComment(
                id = item.optLong("commentId"),
                nickname = user?.optString("nickname").orEmpty(),
                avatarUrl = user?.optString("avatarUrl")?.takeIf { it.startsWith("http") },
                content = item.optString("content"),
                likedCount = item.optLong("likedCount"),
                timeMs = item.optLong("time"),
                timeText = item.optString("timeStr").takeIf { it.isNotBlank() && it != "null" },
                location = item.optJSONObject("ipLocation")?.optString("location")?.takeIf { it.isNotBlank() && it != "null" },
                repliedNickname = replied?.optJSONObject("user")?.optString("nickname")?.takeIf { it.isNotBlank() },
                repliedContent = replied?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
            )
        }
        val more = if (sort == NeteaseCommentSort.HOT) root.optBoolean("hasMore") else root.optBoolean("more")
        return NeteaseCommentPage(comments, root.optLong("total"), more && comments.isNotEmpty())
    }
}

/** Scores NetEase search results against the player's metadata. */
internal object NeteaseSongMatcher {
    private val versionMarkers = listOf(
        "cover", "翻唱", "翻自", "原唱", "伴奏", "instrumental", "karaoke", "live", "现场", "remix", "dj", "铃声", "纯音乐",
        "钢琴", "吉他", "piano", "guitar", "女声", "男声", "版", "加速", "降调", "sped up", "slowed"
    )

    fun best(songs: List<NeteaseSong>, title: String, artist: String, durationMs: Long): NeteaseSong? =
        songs.map { it to score(it, title, artist, durationMs) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first

    fun score(song: NeteaseSong, title: String, artist: String, durationMs: Long): Int {
        val wantedTitle = normalize(title)
        val gotTitle = normalize(song.name)
        var score = when {
            wantedTitle.isEmpty() -> 0
            gotTitle == wantedTitle -> 50
            gotTitle.startsWith(wantedTitle) || wantedTitle.startsWith(gotTitle) -> 32
            gotTitle.contains(wantedTitle) || wantedTitle.contains(gotTitle) -> 22
            else -> -20
        }
        val wantedArtists = splitArtists(artist)
        val gotArtists = (song.artists + song.artistAliases).map(::normalize).filter { it.isNotEmpty() }
        if (wantedArtists.isNotEmpty() && gotArtists.isNotEmpty()) {
            val matched = wantedArtists.count { wanted -> gotArtists.any { it == wanted || it.contains(wanted) || wanted.contains(it) } }
            score += if (matched > 0) 25 + 5 * (matched - 1).coerceAtMost(2) else -15
        }
        if (durationMs > 0L && song.durationMs > 0L) {
            val diff = abs(durationMs - song.durationMs)
            score += when {
                diff <= 2_000L -> 20
                diff <= 5_000L -> 12
                diff <= 15_000L -> 0
                else -> -15
            }
        }
        val rawWanted = title.lowercase(Locale.ROOT)
        val rawGot = song.name.lowercase(Locale.ROOT)
        if (versionMarkers.any { rawGot.contains(it) && !rawWanted.contains(it) }) score -= 35
        return score
    }

    internal fun normalize(value: String): String {
        val base = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("[(（\\[【].*?[)）\\]】]"), " ")
            .replace(Regex("\\s+-\\s+.*$"), " ")
        return base.filter { it.isLetterOrDigit() }
    }

    private fun splitArtists(value: String): List<String> =
        value.split(Regex("\\s*(?:,|，|、|/|&|;|；| feat\\.? | ft\\.? | x | and )\\s*", RegexOption.IGNORE_CASE))
            .map(::normalize)
            .filter { it.isNotEmpty() }

    private const val MIN_SCORE = 30
}
