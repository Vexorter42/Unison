package com.musichub.data

import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import com.musichub.youtube.YtMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class FavoriteTrack(
    val track: Track,
    val addedAt: Long,
    val sentYandex: Boolean = false,
    val sentYoutube: Boolean = false,
) {
    val key: String get() = track.key
}

/** Итог отправки лайков в сервис: сколько ушло и сколько не нашлось там. */
data class SendResult(val sent: Int, val notFound: List<Track>)

/**
 * «Любимое Unison» — собственный плейлист приложения (favorites.json). Сердечко кладёт трек сюда и никуда больше;
 * в Яндекс или YouTube лайки уходят только по кнопке «Отправить».
 */
class Favorites(
    private val yandex: YandexApi,
    private val youtube: YouTubeApi,
    private val ytMusic: YtMusicApi,
    private val file: File = File(AppDirs.root, "favorites.json"),
) {
    private val serializer = ListSerializer(FavoriteTrack.serializer())
    private val lock = Mutex()

    private val _items = MutableStateFlow(read())
    /** Новые сверху. */
    val items: StateFlow<List<FavoriteTrack>> = _items.asStateFlow()

    private val _keys = MutableStateFlow(_items.value.mapTo(HashSet()) { it.key })
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun contains(track: Track): Boolean = track.key in keys.value

    /** Добавить или убрать. Возвращает true, если трек теперь в «Любимом». */
    suspend fun toggle(track: Track): Boolean {
        return if (contains(track)) {
            edit { list -> list.filterNot { it.key == track.key } }
            false
        } else {
            add(listOf(track))
            true
        }
    }

    /**
     * Добавить треки. Новые встают наверх (как в «Мне нравится»).
     * [alreadyInYandex] — треки и так лайкнуты в Яндексе (взяты оттуда), повторно отправлять не нужно.
     */
    suspend fun add(tracks: List<Track>, alreadyInYandex: Boolean = false, alreadyInYoutube: Boolean = false): Int {
        val now = System.currentTimeMillis()
        val rows = tracks.distinctBy { it.key }.mapIndexed { i, t ->
            // Чтобы порядок исходного плейлиста сохранился: первый трек — самый «свежий»
            FavoriteTrack(t.copy(mixed = false), addedAt = now - i, sentYandex = alreadyInYandex, sentYoutube = alreadyInYoutube)
        }
        val keys = rows.mapTo(HashSet()) { it.key }
        edit { list -> (rows + list.filterNot { it.key in keys }).sortedByDescending { it.addedAt } }
        return rows.size
    }

    /** Отправить новые лайки в «Мне нравится» Яндекса. Треки из других сервисов сначала ищутся в Яндексе. */
    suspend fun sendToYandex(onProgress: (done: Int, total: Int) -> Unit): SendResult {
        val pending = items.value.filter { !it.sentYandex }
        val sent = HashSet<String>()
        val notFound = mutableListOf<Track>()
        pending.forEachIndexed { i, row ->
            onProgress(i, pending.size)
            val t = row.track
            val target = if (t.source == Source.YANDEX) t else findOnYandex(t)
            if (target != null && runCatching { yandex.setLiked(target, true) }.isSuccess) sent += row.key
            else notFound += t
        }
        if (sent.isNotEmpty()) edit { list -> list.map { if (it.key in sent) it.copy(sentYandex = true) else it } }
        return SendResult(sent.size, notFound)
    }

    /** То же для YouTube Music (нужен вход в аккаунт YouTube). */
    suspend fun sendToYoutube(onProgress: (done: Int, total: Int) -> Unit): SendResult {
        val pending = items.value.filter { !it.sentYoutube }
        val sent = HashSet<String>()
        val notFound = mutableListOf<Track>()
        pending.forEachIndexed { i, row ->
            onProgress(i, pending.size)
            val t = row.track
            val videoId = if (t.source == Source.YOUTUBE) t.sourceId
            else t.alternates.firstOrNull { it.source == Source.YOUTUBE }?.sourceId
                ?: runCatching { youtube.findMatch(t)?.sourceId }.getOrNull()
            if (videoId != null && runCatching { ytMusic.like(videoId) }.isSuccess) sent += row.key
            else notFound += t
        }
        if (sent.isNotEmpty()) edit { list -> list.map { if (it.key in sent) it.copy(sentYoutube = true) else it } }
        return SendResult(sent.size, notFound)
    }

    private suspend fun findOnYandex(t: Track): Track? {
        t.alternates.firstOrNull { it.source == Source.YANDEX }?.let { return t.copy(source = Source.YANDEX, sourceId = it.sourceId) }
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { yandex.search("${q.artists} ${q.title}") }.getOrNull() ?: return null
        return TrackMatcher.best(q, results, threshold = 0.65)
    }

    private suspend fun edit(block: (List<FavoriteTrack>) -> List<FavoriteTrack>) = lock.withLock {
        val next = block(_items.value)
        _items.value = next
        _keys.value = next.mapTo(HashSet()) { it.key }
        withContext(Dispatchers.IO) { writeAtomic(file, StoreJson.encodeToString(serializer, next)) }
    }

    private fun read(): List<FavoriteTrack> = runCatching {
        StoreJson.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())
}
