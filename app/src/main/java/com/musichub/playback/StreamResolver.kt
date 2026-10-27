package com.musichub.playback

import android.net.Uri
import androidx.core.net.toUri
import com.musichub.AppGraph
import com.musichub.data.Quality
import com.musichub.data.model.Source
import com.musichub.data.model.Track
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
            if (!s.fallbackToOthers) throw e.asIo()
            fallback(uri, source, s.quality, e)
        }
        memo[key] = Resolved(url, System.currentTimeMillis())
        return url.toUri()
    }

    private fun direct(source: Source, id: String, q: Quality): String = when (source) {
        Source.YANDEX -> graph.yandex.streamUrlBlocking(id, q)
        Source.VK -> graph.vk.streamUrlBlocking(id)
        Source.YOUTUBE -> graph.youtube.streamUrlBlocking(id, q)
    }

    private fun fallback(uri: Uri, source: Source, q: Quality, cause: Exception): String {
        // 1) Копии трека, найденные при объединённом поиске
        for (alt in Source.entries) {
            if (alt == source) continue
            val altId = uri.getQueryParameter("alt_${alt.prefix}") ?: continue
            runCatching { direct(alt, altId, q) }.onSuccess { markFallback(uri); return it }
        }
        // 2) Ищем тот же трек на YouTube
        if (source != Source.YOUTUBE) {
            val target = Track(
                source = source,
                sourceId = "",
                title = uri.getQueryParameter("t").orEmpty(),
                artists = uri.getQueryParameter("a").orEmpty(),
                durationMs = uri.getQueryParameter("d")?.toLongOrNull() ?: 0,
            )
            graph.youtube.findMatchBlocking(target)?.let { match ->
                val url = graph.youtube.streamUrlBlocking(match.sourceId, q)
                markFallback(uri)
                return url
            }
        }
        throw IOException("${cause.message}. Замена не найдена", cause)
    }

    private fun markFallback(uri: Uri) = _fallbackKeys.update { it + cacheKey(uri) }

    companion object {
        const val SCHEME = "mh"
        private const val TTL_MS = 20 * 60_000L

        /** Совпадает с [Track.key]. */
        fun cacheKey(uri: Uri): String = "${uri.authority}:${uri.lastPathSegment}"

        fun uriFor(track: Track): Uri = Uri.Builder()
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
