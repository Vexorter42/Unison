package com.musichub.data

import com.musichub.AppGraph
import com.musichub.data.db.FavoriteTrack
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Итог отправки лайков в сервис: сколько ушло и сколько не нашлось там. */
data class SendResult(val sent: Int, val notFound: List<Track>)

/**
 * «Любимое Unison» — собственный плейлист приложения. По выбору пользователя новый лайк
 * дополнительно отправляется в Яндекс или YouTube Music.
 */
class Favorites(private val graph: AppGraph) {

    private val dao get() = graph.db.favorites()

    val items: Flow<List<FavoriteTrack>> = dao.observeAll()

    val tracks: Flow<List<Track>> = items.map { list -> list.mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() } }

    val keys: StateFlow<Set<String>> = items.map { list -> list.mapTo(HashSet()) { it.trackKey } }
        .stateIn(graph.scope, SharingStarted.Eagerly, emptySet())

    /** Сколько треков ещё не отправлено в Яндекс / YouTube. */
    val unsentYandex: Flow<Int> = items.map { list -> list.count { !it.sentYandex } }
    val unsentYoutube: Flow<Int> = items.map { list -> list.count { !it.sentYoutube } }

    fun contains(track: Track): Boolean = track.key in keys.value

    /** Добавить или убрать. Возвращает true, если трек теперь в «Любимом». */
    suspend fun toggle(track: Track): Boolean {
        return if (contains(track)) {
            dao.delete(track.key)
            false
        } else {
            add(listOf(track))
            true
        }
    }

    /** Отправляет только что поставленный лайк в выбранный сервис и отмечает успешную отправку. */
    suspend fun syncLike(track: Track, target: LikeTarget): Boolean = when (target) {
        LikeTarget.UNISON -> true
        LikeTarget.YANDEX -> {
            val found = if (track.source == Source.YANDEX) track else findOnYandex(track)
            val sent = found != null && runCatching { graph.yandex.setLiked(found, true) }.isSuccess
            if (sent) dao.markSentYandex(listOf(track.key))
            sent
        }
        LikeTarget.YOUTUBE -> {
            val id = if (track.source == Source.YOUTUBE) track.sourceId else
                track.alternates.firstOrNull { it.source == Source.YOUTUBE }?.sourceId
                    ?: runCatching { graph.youtube.findMatch(track)?.sourceId }.getOrNull()
            val sent = id != null && runCatching { graph.ytMusic.like(id) }.isSuccess
            if (sent) dao.markSentYoutube(listOf(track.key))
            sent
        }
        LikeTarget.VK -> {
            val found = if (track.source == Source.VK) track else runCatching {
                val query = TrackMatcher.splitYoutube(track)
                TrackMatcher.best(query, graph.vk.search("${query.artists} ${query.title}"), threshold = 0.65)
            }.getOrNull()
            found != null && runCatching { graph.vk.add(found) }.isSuccess
        }
    }

    /**
     * Добавить треки. Новые встают наверх (как в «Мне нравится»).
     * [alreadyInYandex] — треки и так лайкнуты в Яндексе (взяты оттуда), повторно отправлять не нужно.
     */
    suspend fun add(tracks: List<Track>, alreadyInYandex: Boolean = false, alreadyInYoutube: Boolean = false): Int {
        val now = System.currentTimeMillis()
        val rows = tracks.distinctBy { it.key }.mapIndexed { i, t ->
            FavoriteTrack(
                trackKey = t.key,
                trackJson = t.copy(mixed = false).toJson(),
                // Чтобы порядок исходного плейлиста сохранился: первый трек — самый «свежий»
                addedAt = now - i,
                sentYandex = alreadyInYandex,
                sentYoutube = alreadyInYoutube,
            )
        }
        dao.insert(rows)
        return rows.size
    }

    /** Отправить новые лайки в «Мне нравится» Яндекса. Треки из других сервисов сначала ищутся в Яндексе. */
    suspend fun sendToYandex(onProgress: (done: Int, total: Int) -> Unit): SendResult {
        val pending = dao.all().filter { !it.sentYandex }
        val sent = mutableListOf<String>()
        val notFound = mutableListOf<Track>()
        pending.forEachIndexed { i, row ->
            onProgress(i, pending.size)
            val t = runCatching { Track.fromJson(row.trackJson) }.getOrNull() ?: return@forEachIndexed
            val target = if (t.source == Source.YANDEX) t else findOnYandex(t)
            if (target != null && runCatching { graph.yandex.setLiked(target, true) }.isSuccess) sent += row.trackKey
            else notFound += t
        }
        if (sent.isNotEmpty()) dao.markSentYandex(sent)
        return SendResult(sent.size, notFound)
    }

    /** То же для YouTube Music (нужен вход в аккаунт YouTube). */
    suspend fun sendToYoutube(onProgress: (done: Int, total: Int) -> Unit): SendResult {
        val pending = dao.all().filter { !it.sentYoutube }
        val sent = mutableListOf<String>()
        val notFound = mutableListOf<Track>()
        pending.forEachIndexed { i, row ->
            onProgress(i, pending.size)
            val t = runCatching { Track.fromJson(row.trackJson) }.getOrNull() ?: return@forEachIndexed
            val videoId = if (t.source == Source.YOUTUBE) t.sourceId
            else t.alternates.firstOrNull { it.source == Source.YOUTUBE }?.sourceId
                ?: runCatching { graph.youtube.findMatch(t)?.sourceId }.getOrNull()
            if (videoId != null && runCatching { graph.ytMusic.like(videoId) }.isSuccess) sent += row.trackKey
            else notFound += t
        }
        if (sent.isNotEmpty()) dao.markSentYoutube(sent)
        return SendResult(sent.size, notFound)
    }

    private suspend fun findOnYandex(t: Track): Track? {
        t.alternates.firstOrNull { it.source == Source.YANDEX }?.let { return t.copy(source = Source.YANDEX, sourceId = it.sourceId) }
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { graph.yandex.search("${q.artists} ${q.title}") }.getOrNull() ?: return null
        return TrackMatcher.best(q, results, threshold = 0.65)
    }
}
