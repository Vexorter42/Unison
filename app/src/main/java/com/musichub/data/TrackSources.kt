package com.musichub.data

import com.musichub.AppGraph
import com.musichub.data.model.Track
import com.musichub.vk.VkPlaylist
import kotlinx.coroutines.flow.first

/** Список треков, который можно открыть, перенести или взять за основу «Любимого». */
data class TrackSource(val kind: String, val arg: String, val title: String, val subtitle: String)

/**
 * Единая загрузка любого списка по (kind, arg) — тем же, что у экрана TrackListScreen и у ссылок.
 * kind: liked | yapl | yaalbum | yatrack | yauuid | vkmy | vkpl | ytliked | ytuploads | ytpl | ytvideo |
 *       local | favorites | files | downloads
 */
class TrackSources(private val graph: AppGraph) {

    suspend fun load(kind: String, arg: String): List<Track> = when (kind) {
        "liked" -> graph.yandex.likedTracks()
        "yapl" -> arg.split(':').let { graph.yandex.playlistTracks(it[0], it[1].toLong()) }
        "yaalbum" -> graph.yandex.albumTracks(arg)
        "yatrack" -> graph.yandex.tracksById(listOf(arg))
        "yauuid" -> graph.yandex.playlistByUuid(arg)
        "vkmy" -> graph.vk.myAudios()
        "vkpl" -> arg.split(':').let {
            graph.vk.playlistTracks(VkPlaylist(it[0].toLong(), it[1].toLong(), it.getOrNull(2)?.ifEmpty { null }, "", 0, null))
        }
        "ytliked" -> graph.ytMusic.liked()
        "ytuploads" -> graph.ytMusic.uploads()
        "ytpl" -> graph.ytMusic.playlistTracks(arg)
        "ytvideo" -> listOfNotNull(graph.youtube.track(arg))
        "sctrack" -> listOfNotNull(graph.soundcloud.track(arg))
        "scpl" -> graph.soundcloud.playlist(arg)
        "local" -> graph.library.tracks(arg.toLong())
        "favorites" -> graph.favorites.tracks.first()
        "files" -> graph.localMusic.tracks(refresh = true)
        "downloads" -> graph.db.downloads().observeAll().first().mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() }
        else -> emptyList()
    }

    /** Все списки, которые сейчас доступны (по входам в аккаунты). Сети касается — вызывать из корутины. */
    suspend fun available(): List<TrackSource> {
        val s = graph.settings.current
        val list = mutableListOf<TrackSource>()
        list += TrackSource("favorites", "-", "Любимое", "Unison")
        graph.library.playlists.first().forEach { list += TrackSource("local", it.id.toString(), it.name, "Мой плейлист") }
        if (graph.localMusic.hasPermission()) list += TrackSource("files", "-", "На телефоне", "Файлы")
        if (s.yandexLoggedIn) {
            list += TrackSource("liked", "-", "Мне нравится", "Яндекс Музыка")
            runCatching { graph.yandex.playlists() }.getOrDefault(emptyList()).forEach {
                list += TrackSource("yapl", "${it.ownerUid}:${it.kind}", it.title, "Плейлист Яндекса")
            }
        }
        if (s.ytLoggedIn) {
            list += TrackSource("ytliked", "-", "Понравилось", "YouTube Music")
            list += TrackSource("ytuploads", "-", "Загруженные", "YouTube Music")
            runCatching { graph.ytMusic.playlists() }.getOrDefault(emptyList()).forEach {
                list += TrackSource("ytpl", it.browseId, it.title, "Плейлист YouTube")
            }
        }
        if (s.vkLoggedIn) {
            list += TrackSource("vkmy", "-", "Моя музыка", "ВКонтакте")
            runCatching { graph.vk.playlists() }.getOrDefault(emptyList()).forEach {
                list += TrackSource("vkpl", "${it.ownerId}:${it.id}:${it.accessKey.orEmpty()}", it.title, "Плейлист ВК")
            }
        }
        return list
    }
}
