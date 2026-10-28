package com.musichub.vk

import com.musichub.data.SettingsRepository
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.util.arr
import com.musichub.util.long
import com.musichub.util.obj
import com.musichub.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

class VkException(val code: Int, message: String) : IOException(message)

data class VkPlaylist(
    val ownerId: Long,
    val id: Long,
    val accessKey: String?,
    val title: String,
    val count: Int,
    val coverUrl: String?,
)

/**
 * Аудио ВКонтакте. Официально VK не даёт аудио сторонним приложениям, поэтому используем
 * client_id и User-Agent Kate Mobile — как делают все неофициальные клиенты. Может сломаться
 * в любой момент и формально нарушает правила VK.
 */
class VkApi(private val http: Call.Factory, private val settings: SettingsRepository) {

    companion object {
        private const val API = "https://api.vk.com/method/"
        private const val VERSION = "5.131"
        private const val CLIENT_ID = "2685278"
        const val USER_AGENT =
            "KateMobileAndroid/56 lite-460 (Android 4.4.2; SDK 19; x86; unknown Android SDK built for x86; en)"
        const val OAUTH_URL = "https://oauth.vk.com/authorize?client_id=$CLIENT_ID&scope=audio,offline" +
            "&redirect_uri=https://oauth.vk.com/blank.html&display=mobile&response_type=token&revoke=1"
        private const val PAGE = 200
        private const val MAX_TRACKS = 3000
    }

    private val uid: String
        get() = settings.current.vkUserId?.toString() ?: throw VkException(5, "Войдите во ВКонтакте в настройках")

    private fun call(method: String, params: Map<String, String> = emptyMap(), token: String? = settings.current.vkToken): JsonElement {
        token ?: throw VkException(5, "Войдите во ВКонтакте в настройках")
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
            add("access_token", token)
            add("v", VERSION)
        }.build()
        val request = Request.Builder().url(API + method).header("User-Agent", USER_AGENT).post(body).build()
        // Код 6 — «слишком много запросов в секунду»: VK просит чуть подождать, повторяем пару раз
        repeat(3) { attempt ->
            http.newCall(request).execute().use { resp ->
                val root = Json.parseToJsonElement(resp.body?.string().orEmpty()).obj()
                    ?: throw VkException(-1, "ВК: пустой ответ")
                val e = root["error"].obj() ?: return root["response"] ?: JsonNull
                val code = e.long("error_code")?.toInt() ?: -1
                if (code == 6 && attempt < 2) {
                    Thread.sleep(400L * (attempt + 1))
                    return@use
                }
                throw VkException(code, friendlyError(code, e.str("error_msg")))
            }
        }
        throw VkException(6, "ВК: слишком много запросов, попробуйте чуть позже")
    }

    private fun friendlyError(code: Int, raw: String?): String = when (code) {
        5 -> "ВК: вход устарел или отозван — войдите заново"
        6 -> "ВК: слишком много запросов, попробуйте чуть позже"
        9 -> "ВК временно ограничил запросы к музыке (flood control). Обычно это проходит через 10–60 минут — не входите повторно, просто подождите"
        25 -> "ВК требует подтвердить вход — войдите заново через страницу VK"
        29 -> "ВК: исчерпан лимит запросов на сегодня — попробуйте завтра"
        else -> "ВК: $raw"
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun parse(o: JsonObject): Track? {
        val id = o.long("id") ?: return null
        val owner = o.long("owner_id") ?: return null
        val album = o["album"].obj()
        val thumb = album?.get("thumb").obj()?.let { it.str("photo_300") ?: it.str("photo_270") ?: it.str("photo_600") }
        return Track(
            source = Source.VK,
            sourceId = listOfNotNull("${owner}_$id", o.str("access_key")).joinToString("_"),
            title = o.str("title") ?: return null,
            artists = o.str("artist").orEmpty().ifEmpty { "Неизвестный исполнитель" },
            album = album?.str("title"),
            durationMs = (o.long("duration") ?: 0) * 1000,
            coverUrl = thumb,
            available = o.str("url")?.isNotEmpty() ?: true,
        )
    }

    private fun items(r: JsonElement) = r.obj()?.get("items").arr().mapNotNull { it.obj()?.let(::parse) }

    private fun pageAll(fetch: (offset: Int) -> JsonElement): List<Track> {
        val result = mutableListOf<Track>()
        while (result.size < MAX_TRACKS) {
            val r = fetch(result.size)
            val page = items(r)
            result += page
            val total = r.obj()?.long("count") ?: 0
            if (page.isEmpty() || result.size >= total) break
        }
        return result
    }

    suspend fun profile(token: String): Pair<Long, String> = io {
        val u = call("users.get", token = token).arr().firstOrNull().obj() ?: throw VkException(-1, "ВК: нет профиля")
        // Аудио здесь не дёргаем: при «flood control» вход не должен срываться — ограничение VK временное
        val id = u.long("id") ?: throw VkException(-1, "ВК: нет id")
        id to listOfNotNull(u.str("first_name"), u.str("last_name")).joinToString(" ")
    }

    suspend fun search(query: String): List<Track> = io {
        items(call("audio.search", mapOf("q" to query, "count" to "50", "auto_complete" to "1", "sort" to "2")))
    }

    suspend fun myAudios(): List<Track> = io {
        val owner = uid
        pageAll { off -> call("audio.get", mapOf("owner_id" to owner, "count" to "$PAGE", "offset" to "$off")) }
    }

    suspend fun recommendations(): List<Track> = io { items(call("audio.getRecommendations", mapOf("count" to "100"))) }

    suspend fun playlists(): List<VkPlaylist> = io {
        call("audio.getPlaylists", mapOf("owner_id" to uid, "count" to "100")).obj()?.get("items").arr().mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val cover = o["photo"].obj()?.str("photo_300")
                ?: o["thumbs"].arr().firstOrNull().obj()?.str("photo_300")
            VkPlaylist(
                ownerId = o.long("owner_id") ?: return@mapNotNull null,
                id = o.long("id") ?: return@mapNotNull null,
                accessKey = o.str("access_key"),
                title = o.str("title") ?: "Без названия",
                count = o.long("count")?.toInt() ?: 0,
                coverUrl = cover,
            )
        }
    }

    suspend fun playlistTracks(p: VkPlaylist): List<Track> = io {
        pageAll { off ->
            call("audio.get", buildMap {
                put("owner_id", p.ownerId.toString())
                put("album_id", p.id.toString())
                put("count", "$PAGE")
                put("offset", "$off")
                p.accessKey?.let { put("access_key", it) }
            })
        }
    }

    /** Добавить трек в «Мою музыку». */
    suspend fun add(track: Track) = io {
        val parts = track.sourceId.split('_')
        call("audio.add", mapOf("owner_id" to parts[0], "audio_id" to parts[1]))
        Unit
    }

    fun streamUrlBlocking(sourceId: String): String {
        val o = call("audio.getById", mapOf("audios" to sourceId)).arr().firstOrNull().obj()
            ?: throw VkException(-1, "ВК: трек не найден")
        val url = o.str("url")?.takeIf { it.isNotEmpty() } ?: throw VkException(-1, "ВК: трек недоступен")
        return m3u8ToMp3(url)
    }

    /**
     * VK отдаёт HLS (index.m3u8), но рядом лежит тот же трек в mp3:
     * .../c123/u456/<hash>/audios/<id>/index.m3u8?extra=... -> .../c123/u456/audios/<id>.mp3?extra=...
     */
    private fun m3u8ToMp3(url: String): String {
        if (!url.contains("index.m3u8")) return url
        val m = Regex("""/[0-9a-f]+(/audios)?/([0-9a-f]+)/index\.m3u8""").find(url)
            ?: throw VkException(-1, "ВК: неподдерживаемый формат потока")
        return url.replaceRange(m.range, "${m.groupValues[1]}/${m.groupValues[2]}.mp3")
    }
}
