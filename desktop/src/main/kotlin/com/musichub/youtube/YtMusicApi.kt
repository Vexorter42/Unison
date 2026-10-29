package com.musichub.youtube

import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.util.arr
import com.musichub.util.obj
import com.musichub.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class YtPlaylist(val browseId: String, val title: String, val subtitle: String, val coverUrl: String?)

/**
 * Библиотека YouTube Music через внутреннее API сайта (InnerTube) — так же, как делают открытые клиенты.
 * Авторизация — cookie после входа в аккаунт Google и заголовок SAPISIDHASH.
 */
class YtMusicApi(private val http: Call.Factory, private val cookie: () -> String?) {

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val BASE = "$ORIGIN/youtubei/v1/"
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_PAGES = 60
        private val DURATION = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------- HTTP ----------

    /** Версия веб-клиента в формате, который ждёт YouTube Music: 1.ГГГГММДД.01.00 */
    private fun clientVersion(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "1.${fmt.format(Date(System.currentTimeMillis() - 2 * 86_400_000L))}.01.00"
    }

    private fun body(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", clientVersion())
                put("hl", "ru")
                put("gl", "RU")
            }
        }
        extra()
    }

    private fun sapisidHash(cookie: String): String? {
        val sapisid = Regex("""(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)""").find(cookie)?.groupValues?.get(1) ?: return null
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    private fun post(endpoint: String, json: JsonObject, query: String = "", cookieOverride: String? = null): JsonObject {
        val c = cookieOverride ?: cookie()
        val request = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false$query")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0")
            .apply {
                if (c != null) {
                    header("Cookie", c)
                    sapisidHash(c)?.let { header("Authorization", it) }
                }
            }
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw IOException("YouTube: вход устарел — войдите заново в настройках")
            if (!resp.isSuccessful) throw IOException("YouTube: HTTP ${resp.code}")
            return Json.parseToJsonElement(text).obj() ?: throw IOException("YouTube: пустой ответ")
        }
    }

    // ---------- Разбор ответа ----------

    private fun findAll(e: JsonElement?, key: String, out: MutableList<JsonElement> = mutableListOf()): MutableList<JsonElement> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                if (k == key) out += v
                findAll(v, key, out)
            }
            is JsonArray -> e.forEach { findAll(it, key, out) }
            else -> Unit
        }
        return out
    }

    private fun runsText(e: JsonElement?): String {
        val o = e.obj() ?: return ""
        return o.str("simpleText") ?: o["runs"].arr().joinToString("") { it.obj()?.str("text").orEmpty() }
    }

    private fun parseDuration(text: String?): Long {
        if (text == null || !DURATION.matches(text)) return 0
        return text.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000
    }

    /** Одна строка списка треков (плейлист, лайки, загрузки) → Track. */
    internal fun parseItem(r: JsonObject): Track? {
        val videoId = r["playlistItemData"].obj()?.str("videoId")
            ?: findAll(r, "videoId").firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }
            ?: return null
        val cols = r["flexColumns"].arr().map {
            runsText(it.obj()?.get("musicResponsiveListItemFlexColumnRenderer").obj()?.get("text"))
        }
        val title = cols.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val parts = cols.drop(1).flatMap { it.split(" • ") }.map { it.trim() }.filter { it.isNotBlank() }
        val fixed = r["fixedColumns"].arr().firstOrNull().obj()?.get("musicResponsiveListItemFixedColumnRenderer").obj()?.get("text")
        val durationText = runsText(fixed).ifBlank { null } ?: parts.lastOrNull { DURATION.matches(it) }
        val textParts = parts.filterNot { DURATION.matches(it) }
        val thumb = findAll(r["thumbnail"], "thumbnails").firstOrNull().arr().lastOrNull().obj()?.str("url")
        return Track(
            source = Source.YOUTUBE,
            sourceId = videoId,
            title = title,
            artists = textParts.firstOrNull() ?: "YouTube",
            album = textParts.getOrNull(1),
            durationMs = parseDuration(durationText),
            coverUrl = thumb,
        )
    }

    private fun itemsIn(e: JsonElement?): List<Track> =
        findAll(e, "musicResponsiveListItemRenderer").mapNotNull { it.obj()?.let(::parseItem) }

    /** Токен продолжения внутри полки с треками (не у всей страницы — там подгружаются рекомендации). */
    private fun continuationOf(e: JsonElement?): Pair<String, Boolean>? {
        findAll(e, "continuationItemRenderer").firstNotNullOfOrNull {
            findAll(it, "continuationCommand").firstOrNull().obj()?.str("token")
        }?.let { return it to true }
        findAll(e, "nextContinuationData").firstNotNullOfOrNull { it.obj()?.str("continuation") }?.let { return it to false }
        return null
    }

    /** Все треки страницы-списка: плейлист (VL…), лайки (VLLM), загрузки. Догружает продолжения. */
    internal fun browseTracksBlocking(browseId: String): List<Track> {
        val first = post("browse", body { put("browseId", browseId) })
        val shelf = findAll(first, "musicPlaylistShelfRenderer").firstOrNull() ?: findAll(first, "musicShelfRenderer").firstOrNull()
        val tracks = LinkedHashMap<String, Track>()
        itemsIn(shelf).forEach { tracks.putIfAbsent(it.sourceId, it) }
        var next = continuationOf(shelf)
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val (token, newStyle) = next
            val page = if (newStyle) post("browse", body { put("continuation", token) })
            else post("browse", body(), query = "&ctoken=$token&continuation=$token&type=next")
            val before = tracks.size
            itemsIn(page).forEach { tracks.putIfAbsent(it.sourceId, it) }
            if (tracks.size == before) break
            next = continuationOf(page)
        }
        return tracks.values.toList()
    }

    // ---------- Публичное API ----------

    /** Проверяет cookie и возвращает имя аккаунта. */
    suspend fun accountName(cookie: String): String = io {
        val resp = post("account/account_menu", body(), cookieOverride = cookie)
        findAll(resp, "accountName").firstOrNull()?.let(::runsText)?.ifBlank { null }
            ?: throw IOException("YouTube: не удалось войти — сессия не принята")
    }

    suspend fun liked(): List<Track> = io { browseTracksBlocking("VLLM") }

    /** Свои загруженные в YouTube Music песни. */
    suspend fun uploads(): List<Track> = io { browseTracksBlocking("FEmusic_library_privately_owned_tracks") }

    suspend fun playlistTracks(browseId: String): List<Track> = io {
        browseTracksBlocking(if (browseId.startsWith("VL") || browseId.startsWith("FE")) browseId else "VL$browseId")
    }

    suspend fun playlists(): List<YtPlaylist> = io {
        val resp = post("browse", body { put("browseId", "FEmusic_liked_playlists") })
        findAll(resp, "musicTwoRowItemRenderer").mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val id = o["navigationEndpoint"].obj()?.get("browseEndpoint").obj()?.str("browseId") ?: return@mapNotNull null
            if (!id.startsWith("VL") || id == "VLLM") return@mapNotNull null
            YtPlaylist(
                browseId = id,
                title = runsText(o["title"]).ifBlank { "Без названия" },
                subtitle = runsText(o["subtitle"]),
                coverUrl = findAll(o["thumbnailRenderer"], "thumbnails").firstOrNull().arr().lastOrNull().obj()?.str("url"),
            )
        }.distinctBy { it.browseId }
    }

    suspend fun like(videoId: String) = io {
        post("like/like", body { putJsonObject("target") { put("videoId", videoId) } })
        Unit
    }

    /** Новый приватный плейлист с треками (для переноса). Возвращает его id. */
    suspend fun createPlaylist(title: String, videoIds: List<String>): String = io {
        val resp = post("playlist/create", body {
            put("title", title)
            put("description", "Перенесено из Unison")
            put("privacyStatus", "PRIVATE")
            putJsonArray("videoIds") { addAll(videoIds.distinct()) }
        })
        resp.str("playlistId") ?: throw IOException("YouTube: плейлист не создался")
    }
}
