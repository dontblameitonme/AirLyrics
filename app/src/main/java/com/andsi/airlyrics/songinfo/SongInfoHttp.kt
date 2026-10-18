package com.andsi.airlyrics.songinfo

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Small blocking HTTP helper shared by the song-info clients. Call from a background thread. */
internal object SongInfoHttp {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    fun open(url: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = READ_TIMEOUT_MS): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    fun getText(url: String, headers: Map<String, String>): String {
        val connection = open(url, headers)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
