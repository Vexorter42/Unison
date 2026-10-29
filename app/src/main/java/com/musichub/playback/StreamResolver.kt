package com.musichub.playback

import com.musichub.search.TrackMatcher
import android.net.Uri
import androidx.core.net.toUri
import com.musichub.AppGraph
import com.musichub.data.Quality
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.local.LocalMusic
import com.musichub.util.asIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Треки в плеере имеют адрес вида mh://ya/123:456?t=…&a=…, а настоящая ссылка на файл
 * получается в момент воспроизведения (ссылки сервисов живут недолго).
 * Благодаря этому кеш хранится по стабильному ключу, и скачанное играет без интернета.
 */
class StreamResolver(private val graph: AppGraph) {

    private class Resolved(val url: String, val at: Long)

    private val memo = ConcurrentHashMap<String, Resolved>()
    private val _fallbackKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Треки, которые играют не из своего сервиса, а из замены. */
    val fallbackKeys: StateFlow<Set<String>> = _fallbackKeys.asStateFlow()

    /** Вызывается на потоке загрузки плеера — блокирующие запросы здесь допустимы. */
    fun resolve(uri: Uri): Uri {
        val key = cacheKey(uri)
        memo[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.url.toUri() }

        val s = graph.settings.current
        val id = uri.lastPathSegment ?: throw IOException("Некорректный адрес $uri")
        val source = Source.entries.firstOrNull { it.prefix == uri.authority } ?: throw IOException("Неизвестный источник")
        val url = try {
            direct(source, id, s.quality)
        } catch (e: Exception) {
            if (!s.fallbackToOthers || source == Source.SOUNDCLOUD) throw e.asIo()
            fallback(uri, source, s.quality, e)
        }
        memo[key] = Resolved(url, System.currentTimeMillis())
        return url.toUri()
    }

    private fun direct(source: Source, id: String, q: Quality): String = when (source) {
        Source.YANDEX -> graph.yandex.streamUrlBlocking(id, q)
        Source.VK -> graph.vk.streamUrlBlocking(id)
        Source.YOUTUBE -> graph.youtube.streamUrlBlocking(id, q)
        Source.LOCAL -> LocalMusic.contentUri(id).toString()
        Source.SOUNDCLOUD -> graph.soundcloud.streamUrlBlocking(id)
    }

    private fun fallback(uri: Uri, source: Source, q: Quality, cause: Exception): String {
        // 1) Копии трека, найденные при объединённом поиске
        for (alt in Source.entries) {
            if (alt == source) continue
            val altId = uri.getQueryParameter("alt_${alt.prefix}") ?: continue
            runCatching { direct(alt, altId, q) }.onSuccess { markFallback(uri); return it }
        }
        val target = Track(
            source = source,
            sourceId = "",
            title = uri.getQueryParameter("t").orEmpty(),
            artists = uri.getQueryParameter("a").orEmpty(),
            durationMs = uri.getQueryParameter("d")?.toLongOrNull() ?: 0,
        )
        // 2) Трек с YouTube не играет (например, YouTube просит «подтвердить, что вы не бот») — ищем в Яндексе
        if (source != Source.YANDEX && graph.settings.current.yandexLoggedIn) {
            val q2 = TrackMatcher.splitYoutube(target)
            val match = runCatching { TrackMatcher.best(q2, graph.yandex.searchBlocking("${q2.artists} ${q2.title}"), threshold = 0.65) }.getOrNull()
            if (match != null) {
                runCatching { graph.yandex.streamUrlBlocking(match.sourceId, q) }.onSuccess { markFallback(uri); return it }
            }
        }
        // 3) Ищем тот же трек на YouTube
        if (source != Source.YOUTUBE) {
            graph.youtube.findMatchBlocking(target)?.let { match ->
                val url = graph.youtube.streamUrlBlocking(match.sourceId, q)
                markFallback(uri)
                return url
            }
        }
        val reason = cause.message.orEmpty()
        if ("not a bot" in reason || "LOGIN_REQUIRED" in reason) {
            throw IOException("YouTube временно не пускает с этого IP (просит подтвердить, что вы не бот). Замена в Яндексе не нашлась", cause)
        }
        throw IOException("$reason. Замена не найдена", cause)
    }

    private fun markFallback(uri: Uri) = _fallbackKeys.update { it + cacheKey(uri) }

    companion object {
        const val SCHEME = "mh"
        private const val TTL_MS = 20 * 60_000L

        /** Совпадает с [Track.key]. */
        fun cacheKey(uri: Uri): String = "${uri.authority}:${uri.lastPathSegment}"

        fun uriFor(track: Track): Uri = if (track.source == Source.LOCAL) LocalMusic.contentUri(track.sourceId) else Uri.Builder()
            .scheme(SCHEME)
            .authority(track.source.prefix)
            .appendPath(track.sourceId)
            .appendQueryParameter("t", track.title)
            .appendQueryParameter("a", track.artists)
            .appendQueryParameter("d", track.durationMs.toString())
            .apply { track.alternates.forEach { appendQueryParameter("alt_${it.source.prefix}", it.sourceId) } }
            .build()
    }
}
