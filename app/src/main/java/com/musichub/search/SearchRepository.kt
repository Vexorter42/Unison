package com.musichub.search

import com.musichub.data.SettingsRepository
import com.musichub.data.model.Track
import com.musichub.data.model.TrackRef
import com.musichub.local.LocalMusic
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SearchResult(val tracks: List<Track>, val errors: List<String>)

/** Единый поиск: Яндекс + ВК + YouTube, одинаковые треки склеиваются в один. */
class SearchRepository(
    private val yandex: YandexApi,
    private val vk: VkApi,
    private val youtube: YouTubeApi,
    private val local: LocalMusic,
    private val settings: SettingsRepository,
) {
    suspend fun search(query: String): SearchResult = coroutineScope {
        val s = settings.current
        val ya = async { runCatching { yandex.search(query) } }
        val vkRes = async { if (s.searchVk && s.vkLoggedIn) runCatching { vk.search(query) } else Result.success(emptyList()) }
        val yt = async { if (s.searchYoutube) runCatching { youtube.search(query) } else Result.success(emptyList()) }
        val files = async { runCatching { local.search(query) } }
        val results = listOf("Яндекс" to ya.await(), "ВК" to vkRes.await(), "YouTube" to yt.await())

        // Порядок важен: у Яндекса лучшие метаданные, поэтому он «главный», остальные — подмешиваются
        var merged = emptyList<Track>()
        for ((_, r) in results) merged = merge(merged, r.getOrDefault(emptyList()))
        // Свои файлы — наверху: раз они на телефоне, их скорее всего и ищут
        SearchResult(
            tracks = files.await().getOrDefault(emptyList()) + merged,
            errors = results.mapNotNull { (name, r) -> r.exceptionOrNull()?.let { "$name: ${it.message}" } },
        )
    }

    private fun merge(base: List<Track>, extra: List<Track>): List<Track> {
        val result = base.toMutableList()
        val added = mutableListOf<Track>()
        for (t in extra) {
            val idx = result.indexOfFirst { existing ->
                existing.alternates.none { it.source == t.source } && TrackMatcher.score(existing, t) >= 0.78
            }
            if (idx >= 0) {
                val e = result[idx]
                result[idx] = e.copy(alternates = e.alternates + TrackRef(t.source, t.sourceId))
            } else {
                added += t
            }
        }
        return result + added
    }
}
