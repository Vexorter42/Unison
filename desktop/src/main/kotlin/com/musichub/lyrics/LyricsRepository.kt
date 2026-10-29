package com.musichub.lyrics

import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import com.musichub.util.arr
import com.musichub.util.obj
import com.musichub.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: List<LyricLine>?, val plain: String?) {
    val isEmpty: Boolean get() = synced.isNullOrEmpty() && plain.isNullOrBlank()
}

/** Тексты песен из LRCLIB (бесплатная открытая база, есть синхронизированные тексты). */
class LyricsRepository(private val http: OkHttpClient) {

    private val memo = ConcurrentHashMap<String, Lyrics>()

    suspend fun get(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        memo[track.key]?.let { return@withContext it.takeUnless { l -> l.isEmpty } }
        val t = TrackMatcher.splitYoutube(track)
        val artist = t.artists.substringBefore(',').trim()
        val title = t.title.replace(Regex("""\s*\((feat|ft)[^)]*\)""", RegexOption.IGNORE_CASE), "").trim()

        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .apply { if (t.durationMs > 0) addQueryParameter("duration", (t.durationMs / 1000).toString()) }
            .build()
        val found = fetch(exact).obj() ?: run {
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$artist $title").build()
            val list = fetch(search).arr().mapNotNull { it.obj() }
            list.firstOrNull { it.str("syncedLyrics") != null } ?: list.firstOrNull()
        }
        val lyrics = Lyrics(
            synced = found?.str("syncedLyrics")?.let(::parseLrc)?.takeIf { it.isNotEmpty() },
            plain = found?.str("plainLyrics"),
        )
        memo[track.key] = lyrics
        lyrics.takeUnless { it.isEmpty }
    }

    private fun fetch(url: HttpUrl): JsonElement? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Unison Desktop (personal music player)").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else Json.parseToJsonElement(resp.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun parseLrc(lrc: String): List<LyricLine> {
        val tag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
        return lrc.lines().flatMap { line ->
            val text = line.replace(tag, "").trim()
            tag.findAll(line).map { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].let { if (it.isEmpty()) 0 else it.padEnd(3, '0').take(3).toLong() }
                LyricLine(min * 60_000 + sec * 1000 + frac, text)
            }.toList()
        }.sortedBy { it.timeMs }
    }
}
