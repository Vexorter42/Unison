package com.musichub.search

import com.musichub.data.SettingsRepository
import com.musichub.data.model.Track
import com.musichub.data.model.TrackRef
import com.musichub.local.LocalMusic
import com.musichub.soundcloud.SoundCloudApi
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Единый поиск: Яндекс + ВК + YouTube, одинаковые треки склеиваются в один. */
class SearchRepository(
    private val yandex: YandexApi,
    private val vk: VkApi,
    private val youtube: YouTubeApi,
    private val soundcloud: SoundCloudApi,
    private val local: LocalMusic,
    private val settings: SettingsRepository,
) {
    suspend fun search(query: String, category: SearchCategory = SearchCategory.TRACKS): SearchResult = coroutineScope {
        val s = settings.current
        when (category) {
            SearchCategory.TRACKS -> searchTracks(query, s)
            SearchCategory.ARTISTS -> {
                val ya = async { runCatching { yandex.searchArtists(query) } }
                val yt = async { if (s.searchYoutube) runCatching { youtube.searchArtists(query) } else Result.success(emptyList()) }
                val results = listOf("Яндекс" to ya.await(), "YouTube" to yt.await())
                SearchResult(
                    artists = results.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { "${it.source}:${it.id}" },
                    errors = results.mapNotNull { (name, r) -> r.exceptionOrNull()?.let { "$name: ${it.message}" } },
                )
            }
            SearchCategory.ALBUMS -> {
                val ya = async { runCatching { yandex.searchAlbums(query) } }
                val yt = async { if (s.searchYoutube) runCatching { youtube.searchAlbums(query) } else Result.success(emptyList()) }
                val results = listOf("Яндекс" to ya.await(), "YouTube" to yt.await())
                SearchResult(
                    albums = results.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { "${it.source}:${it.id}" },
                    errors = results.mapNotNull { (name, r) -> r.exceptionOrNull()?.let { "$name: ${it.message}" } },
                )
            }
            SearchCategory.PLAYLISTS -> {
                val ya = async { runCatching { yandex.searchPlaylists(query) } }
                val yt = async { if (s.searchYoutube) runCatching { youtube.searchPlaylists(query) } else Result.success(emptyList()) }
                val results = listOf("Яндекс" to ya.await(), "YouTube" to yt.await())
                SearchResult(
                    playlists = results.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { "${it.source}:${it.id}" },
                    errors = results.mapNotNull { (name, r) -> r.exceptionOrNull()?.let { "$name: ${it.message}" } },
                )
            }
        }
    }

    private suspend fun searchTracks(query: String, s: com.musichub.data.AppSettings): SearchResult = coroutineScope {
        val ya = async { runCatching { yandex.search(query) } }
        val vkRes = async { if (s.searchVk && s.vkLoggedIn) runCatching { vk.search(query) } else Result.success(emptyList()) }
        val yt = async { if (s.searchYoutube) runCatching { youtube.search(query) } else Result.success(emptyList()) }
        val files = async { runCatching { local.search(query) } }
        val sc = async { if (s.searchSoundcloud) runCatching { soundcloud.search(query) } else Result.success(emptyList()) }
        val results = listOf("Яндекс" to ya.await(), "ВК" to vkRes.await(), "YouTube" to yt.await(), "SoundCloud" to sc.await())
        var merged = emptyList<Track>()
        for ((_, r) in results) merged = merge(merged, r.getOrDefault(emptyList()))
        SearchResult(
            tracks = files.await().getOrDefault(emptyList()) + merged,
            errors = results.mapNotNull { (name, r) -> r.exceptionOrNull()?.let { "$name: ${it.message}" } },
        )
    }

    suspend fun loadCollection(item: CollectionSearchResult): List<Track> = when (item.source) {
        com.musichub.data.model.Source.YANDEX -> when (item.kind) {
            CollectionKind.ALBUM -> yandex.albumTracks(item.id)
            CollectionKind.PLAYLIST -> item.id.split(':').let { yandex.playlistTracks(it[0], it[1].toLong()) }
        }
        com.musichub.data.model.Source.YOUTUBE -> youtube.playlist(item.url ?: item.id).second
        else -> emptyList()
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
