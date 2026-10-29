package com.musichub.playback

import com.musichub.data.Quality
import com.musichub.data.SettingsRepository
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import com.musichub.util.asIo
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Ссылка на файл и признак, что она взята не из сервиса трека, а из замены. */
data class ResolvedStream(val url: String, val viaFallback: Boolean)

/**
 * Получает настоящую ссылку на файл в момент воспроизведения (ссылки сервисов живут недолго).
 * Если свой сервис трек не отдаёт — ищет замену: копии из объединённого поиска, затем Яндекс, затем YouTube.
 * Методы блокирующие — вызывать на IO.
 */
class StreamResolver(
    private val settings: SettingsRepository,
    private val yandex: YandexApi,
    private val vk: VkApi,
    private val youtube: YouTubeApi,
) {
    private class Memo(val stream: ResolvedStream, val at: Long)

    private val memo = ConcurrentHashMap<String, Memo>()

    /** [skipPrimary] — свой сервис уже подвёл (ссылка получена, но не играет), сразу ищем замену. */
    fun resolve(track: Track, skipPrimary: Boolean = false): ResolvedStream {
        if (!skipPrimary) memo[track.key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.stream }
        val s = settings.current
        val stream = try {
            if (skipPrimary) throw IOException("Источник «${track.source.label}» не играет")
            ResolvedStream(direct(track.source, track.sourceId, s.quality), viaFallback = false)
        } catch (e: Exception) {
            if (!s.fallbackToOthers) throw e.asIo()
            ResolvedStream(fallback(track, s.quality, e), viaFallback = true)
        }
        memo[track.key] = Memo(stream, System.currentTimeMillis())
        return stream
    }

    fun forget(track: Track) {
        memo.remove(track.key)
    }

    private fun direct(source: Source, id: String, q: Quality): String = when (source) {
        Source.YANDEX -> yandex.streamUrlBlocking(id, q)
        Source.VK -> vk.streamUrlBlocking(id)
        Source.YOUTUBE -> youtube.streamUrlBlocking(id, q)
        Source.LOCAL -> java.io.File(id).toURI().toString()
    }

    private fun fallback(track: Track, q: Quality, cause: Exception): String {
        // 1) Копии трека, найденные при объединённом поиске
        for (alt in track.alternates) {
            if (alt.source == track.source) continue
            runCatching { direct(alt.source, alt.sourceId, q) }.onSuccess { return it }
        }
        // 2) Трек с YouTube не играет (например, YouTube просит «подтвердить, что вы не бот») — ищем в Яндексе
        if (track.source != Source.YANDEX && settings.current.yandexLoggedIn) {
            val q2 = TrackMatcher.splitYoutube(track)
            val match = runCatching { TrackMatcher.best(q2, yandex.searchBlocking("${q2.artists} ${q2.title}"), threshold = 0.65) }.getOrNull()
            if (match != null) {
                runCatching { yandex.streamUrlBlocking(match.sourceId, q) }.onSuccess { return it }
            }
        }
        // 3) Ищем тот же трек на YouTube
        if (track.source != Source.YOUTUBE) {
            youtube.findMatchBlocking(track)?.let { match -> return youtube.streamUrlBlocking(match.sourceId, q) }
        }
        val reason = cause.message.orEmpty()
        if ("not a bot" in reason || "LOGIN_REQUIRED" in reason) {
            throw IOException("YouTube временно не пускает с этого IP (просит подтвердить, что вы не бот). Замена в Яндексе не нашлась", cause)
        }
        throw IOException("$reason. Замена не найдена", cause)
    }

    private companion object {
        const val TTL_MS = 20 * 60_000L
    }
}
