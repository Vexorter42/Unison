package com.musichub.yandex

import com.musichub.data.Quality
import com.musichub.data.SettingsRepository
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.util.arr
import com.musichub.util.bool
import com.musichub.util.long
import com.musichub.util.obj
import com.musichub.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs

class YandexException(val code: Int, message: String) : IOException(message)

data class YaPlaylist(
    val ownerUid: Long,
    val kind: Long,
    val title: String,
    val trackCount: Int,
    val coverUrl: String?,
)

data class WaveBatch(val batchId: String?, val tracks: List<Track>)

/**
 * Неофициальное API Яндекс Музыки (то же, что использует их Android-приложение).
 * Блокирующие методы помечены суффиксом Blocking, остальные — suspend и работают на IO.
 */
class YandexApi(private val http: Call.Factory, private val settings: SettingsRepository) {

    companion object {
        private const val BASE = "https://api.music.yandex.net"
        private const val CLIENT = "YandexMusicAndroid/24023621"
        private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
        private const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        const val OAUTH_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=$CLIENT_ID"
        const val WAVE = "user:onyourwave"
    }

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val uid: Long
        get() = settings.current.yandexUid ?: throw YandexException(401, "Войдите в аккаунт Яндекса в настройках")

    // ---------- HTTP ----------

    private fun call(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        token: String? = settings.current.yandexToken,
    ): JsonElement {
        val url = "$BASE$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder().url(url)
            .header("X-Yandex-Music-Client", CLIENT)
            .header("Accept-Language", "ru")
            .apply { if (token != null) header("Authorization", "OAuth $token") }
            .method(method, body ?: if (method == "POST") ByteArray(0).toRequestBody() else null)
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { Json.parseToJsonElement(text).obj()?.get("error").obj()?.str("message") }.getOrNull()
                throw YandexException(resp.code, "Яндекс: ${msg ?: "HTTP ${resp.code}"}")
            }
            return Json.parseToJsonElement(text).obj()?.get("result") ?: JsonNull
        }
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()) = call("GET", path, params)

    private fun postForm(path: String, form: Map<String, String>) =
        call("POST", path, body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())

    private fun postJson(path: String, json: JsonElement, params: Map<String, String> = emptyMap()) =
        call("POST", path, params, json.toString().toRequestBody("application/json".toMediaType()))

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------- Парсинг ----------

    private fun parseTrack(o: JsonObject): Track? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val album = o["albums"].arr().firstOrNull().obj()
        val albumId = album?.str("id")
        val version = o.str("version")?.takeIf { it.isNotBlank() }
        val artists = o["artists"].arr().mapNotNull { it.obj()?.str("name") }.joinToString(", ")
        val cover = (o.str("coverUri") ?: album?.str("coverUri") ?: o.str("ogImage"))
            ?.let { "https://" + it.replace("%%", "400x400") }
        return Track(
            source = Source.YANDEX,
            sourceId = if (albumId != null) "$id:$albumId" else id,
            title = if (version != null) "$title ($version)" else title,
            artists = artists.ifEmpty { "Неизвестный исполнитель" },
            album = album?.str("title"),
            durationMs = o.long("durationMs") ?: 0,
            coverUrl = cover,
            available = o.bool("available") ?: true,
        )
    }

    private fun parsePlaylist(o: JsonObject?): YaPlaylist? {
        o ?: return null
        val cover = o["cover"].obj()
        val coverUri = cover?.str("uri")
            ?: cover?.get("itemsUri").arr().firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: o.str("ogImage")
        return YaPlaylist(
            ownerUid = o["owner"].obj()?.long("uid") ?: o.long("uid") ?: uid,
            kind = o.long("kind") ?: return null,
            title = o.str("title") ?: "Без названия",
            trackCount = o.long("trackCount")?.toInt() ?: 0,
            coverUrl = coverUri?.let { "https://" + it.replace("%%", "200x200") },
        )
    }

    private fun fetchTracks(ids: List<String>): List<Track> = ids.chunked(200).flatMap { chunk ->
        postForm("/tracks", mapOf("track-ids" to chunk.joinToString(","), "with-positions" to "false"))
            .arr().mapNotNull { it.obj()?.let(::parseTrack) }
    }

    // ---------- Аккаунт ----------

    /** Проверяет токен и возвращает uid и логин. */
    suspend fun account(token: String): Pair<Long, String?> = io {
        val acc = call("GET", "/account/status", token = token).obj()?.get("account").obj()
        val id = acc?.long("uid") ?: throw YandexException(401, "Токен не подошёл")
        id to (acc.str("login") ?: acc.str("displayName"))
    }

    // ---------- Поиск и библиотека ----------

    suspend fun search(query: String): List<Track> = io {
        get("/search", mapOf("text" to query, "type" to "track", "page" to "0", "nocorrect" to "false"))
            .obj()?.get("tracks").obj()?.get("results").arr()
            .mapNotNull { it.obj()?.let(::parseTrack) }
    }

    private fun likedRefs(): List<String> {
        val items = get("/users/$uid/likes/tracks").obj()?.get("library").obj()?.get("tracks").arr()
        val ids = items.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            o.str("albumId")?.let { "$id:$it" } ?: id
        }
        _likedIds.value = ids.map { it.substringBefore(':') }.toSet()
        return ids
    }

    suspend fun refreshLikes() = io { likedRefs(); Unit }

    suspend fun likedTracks(): List<Track> = io { fetchTracks(likedRefs()) }

    suspend fun playlists(): List<YaPlaylist> = io {
        get("/users/$uid/playlists/list").arr().mapNotNull { parsePlaylist(it.obj()) }
    }

    suspend fun playlistTracks(owner: Long, kind: Long): List<Track> = io {
        val items = get("/users/$owner/playlists/$kind", mapOf("rich-tracks" to "true")).obj()?.get("tracks").arr()
        val result = mutableListOf<Track>()
        val missing = mutableListOf<String>()
        for (el in items) {
            val o = el.obj() ?: continue
            val t = o["track"].obj()?.let(::parseTrack)
            if (t != null) result += t
            else o.str("id")?.let { id -> missing += o.str("albumId")?.let { "$id:$it" } ?: id }
        }
        if (missing.isNotEmpty()) result += fetchTracks(missing)
        result
    }

    suspend fun setLiked(track: Track, liked: Boolean) = io {
        val id = track.yandexId ?: return@io
        postForm("/users/$uid/likes/tracks/${if (liked) "add-multiple" else "remove"}", mapOf("track-ids" to track.sourceId))
        _likedIds.update { if (liked) it + id else it - id }
    }

    suspend fun dislike(track: Track) = io {
        val id = track.yandexId ?: return@io
        postForm("/users/$uid/dislikes/tracks/add-multiple", mapOf("track-ids" to track.sourceId))
        _likedIds.update { it - id }
    }

    /** Создаёт приватный плейлист и кладёт в него треки (для переноса плейлистов). */
    suspend fun createPlaylist(title: String, tracks: List<Track>): YaPlaylist = io {
        val created = postForm("/users/$uid/playlists/create", mapOf("title" to title, "visibility" to "private")).obj()
        val playlist = parsePlaylist(created) ?: throw YandexException(500, "Не удалось создать плейлист")
        val withAlbums = tracks.filter { it.source == Source.YANDEX && ':' in it.sourceId }
        if (withAlbums.isNotEmpty()) {
            val diff = buildJsonArray {
                addJsonObject {
                    put("op", "insert")
                    put("at", 0)
                    putJsonArray("tracks") {
                        withAlbums.forEach { t ->
                            addJsonObject {
                                put("id", t.sourceId.substringBefore(':'))
                                put("albumId", t.sourceId.substringAfter(':'))
                            }
                        }
                    }
                }
            }
            postForm(
                "/users/$uid/playlists/${playlist.kind}/change-relative",
                mapOf("diff" to diff.toString(), "revision" to (created?.long("revision") ?: 1).toString()),
            )
        }
        playlist.copy(trackCount = withAlbums.size)
    }

    // ---------- Моя волна ----------

    suspend fun waveTracks(lastTrackId: String?): WaveBatch = io {
        val params = buildMap {
            put("settings2", "true")
            if (lastTrackId != null) put("queue", lastTrackId)
        }
        val r = get("/rotor/station/$WAVE/tracks", params).obj()
        WaveBatch(
            batchId = r?.str("batchId"),
            tracks = r?.get("sequence").arr().mapNotNull { it.obj()?.get("track").obj()?.let(::parseTrack) },
        )
    }

    /** Отзывы для волны: radioStarted / trackStarted / trackFinished / skip. Ошибки игнорируем. */
    suspend fun waveFeedback(type: String, batchId: String?, trackId: String? = null, playedSec: Double? = null) {
        io {
            runCatching {
                val body = buildJsonObject {
                    put("type", type)
                    put("timestamp", System.currentTimeMillis() / 1000.0)
                    if (type == "radioStarted") put("from", "mobile-radio-user-onyourwave")
                    trackId?.let { put("trackId", it) }
                    playedSec?.let { put("totalPlayedSeconds", it) }
                }
                postJson("/rotor/station/$WAVE/feedback", body, batchId?.let { mapOf("batch-id" to it) } ?: emptyMap())
            }
        }
    }

    /** mood: all/active/fun/calm/sad, diversity: default/favorite/discover/popular */
    suspend fun setWaveSettings(mood: String, diversity: String) = io {
        postJson("/rotor/station/$WAVE/settings3", buildJsonObject {
            put("moodEnergy", mood)
            put("diversity", diversity)
            put("language", "any")
        })
        Unit
    }

    // ---------- Поток ----------

    fun streamUrlBlocking(sourceId: String, quality: Quality): String {
        val trackId = sourceId.substringBefore(':')
        val infos = get("/tracks/$trackId/download-info").arr().mapNotNull { it.obj() }
            .filter { it.str("codec") == "mp3" || it.str("codec") == "aac" }
        val full = infos.filter { it.bool("preview") != true }
        if (full.isEmpty()) {
            throw YandexException(403, if (infos.isEmpty()) "Яндекс: трек недоступен" else "Яндекс: доступно только превью (нужен Плюс)")
        }
        val sorted = full.filter { it.str("codec") == "mp3" }.ifEmpty { full }.sortedBy { it.long("bitrateInKbps") ?: 0 }
        val pick = when (quality) {
            Quality.HIGH -> sorted.last()
            Quality.LOW -> sorted.first()
            Quality.NORMAL -> sorted.minBy { abs((it.long("bitrateInKbps") ?: 0) - 192) }
        }
        val infoUrl = pick.str("downloadInfoUrl") ?: throw YandexException(500, "Яндекс: нет ссылки на файл")
        val request = Request.Builder().url("$infoUrl&format=json")
            .header("X-Yandex-Music-Client", CLIENT)
            .apply { settings.current.yandexToken?.let { header("Authorization", "OAuth $it") } }
            .build()
        val d = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw YandexException(resp.code, "Яндекс: HTTP ${resp.code} при получении файла")
            Json.parseToJsonElement(resp.body?.string().orEmpty()).obj()
        } ?: throw YandexException(500, "Яндекс: пустой ответ")
        val host = d.str("host")
        val path = d.str("path") ?: throw YandexException(500, "Яндекс: неожиданный ответ")
        val sign = md5(SIGN_SALT + path.substring(1) + d.str("s"))
        return "https://$host/get-mp3/$sign/${d.str("ts")}$path"
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
