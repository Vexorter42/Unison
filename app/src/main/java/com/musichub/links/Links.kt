package com.musichub.links

import android.net.Uri
import com.musichub.data.model.Source
import com.musichub.data.model.Track

/** Куда ведёт ссылка: экран списка треков (kind/arg — как у TrackListScreen) и заголовок. */
data class LinkTarget(val kind: String, val arg: String, val title: String)

/**
 * Ссылки на плейлисты, альбомы и треки Яндекс Музыки, YouTube (Music) и ВКонтакте:
 * разбор входящих (открыть в Unison) и сборка исходящих (поделиться).
 */
object Links {

    /** Достаёт первую ссылку из текста (при «Поделиться» приложения часто добавляют подпись). */
    fun firstUrl(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')', '»', '"')

    fun parse(text: String): LinkTarget? {
        val url = firstUrl(text) ?: return null
        val uri = Uri.parse(url)
        val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.pathSegments
        return when {
            host.startsWith("music.yandex.") -> parseYandex(path)
            host == "youtube.com" || host == "music.youtube.com" || host == "youtu.be" -> parseYoutube(host, uri, path)
            host == "vk.com" || host == "vk.ru" -> parseVk(uri, path)
            host == "soundcloud.com" || host == "on.soundcloud.com" -> parseSoundCloud(path)
            else -> null
        }
    }

    private fun parseYandex(p: List<String>): LinkTarget? = when {
        // /users/<логин>/playlists/<kind>
        p.size >= 4 && p[0] == "users" && p[2] == "playlists" -> LinkTarget("yapl", "${p[1]}:${p[3]}", "Плейлист Яндекса")
        // /album/<id>/track/<id>
        p.size >= 4 && p[0] == "album" && p[2] == "track" -> LinkTarget("yatrack", "${p[3]}:${p[1]}", "Трек")
        // /album/<id>
        p.size >= 2 && p[0] == "album" -> LinkTarget("yaalbum", p[1], "Альбом")
        // /playlists/<uuid> — новый формат «Поделиться»
        p.size >= 2 && p[0] == "playlists" -> LinkTarget("yauuid", p[1], "Плейлист Яндекса")
        else -> null
    }

    private fun parseYoutube(host: String, uri: Uri, p: List<String>): LinkTarget? {
        uri.getQueryParameter("list")?.takeIf { it.isNotBlank() && !it.startsWith("RD") }?.let {
            return LinkTarget("ytpl", "VL$it", "Плейлист YouTube")
        }
        val video = when {
            host == "youtu.be" -> p.firstOrNull()
            else -> uri.getQueryParameter("v")
        }
        return video?.takeIf { it.length == 11 }?.let { LinkTarget("ytvideo", it, "Трек") }
    }

    private fun parseVk(uri: Uri, p: List<String>): LinkTarget? {
        // vk.com/music/playlist/<owner>_<id>_<key>
        if (p.size >= 3 && p[0] == "music" && (p[1] == "playlist" || p[1] == "album")) {
            val parts = p[2].split('_')
            if (parts.size >= 2) return LinkTarget("vkpl", "${parts[0]}:${parts[1]}:${parts.getOrElse(2) { "" }}", "Плейлист ВК")
        }
        // vk.com/audio?act=audio_playlist<owner>_<id>&access_hash=<key>
        uri.getQueryParameter("act")?.removePrefix("audio_playlist")?.split('_')?.takeIf { it.size == 2 }?.let { (o, id) ->
            return LinkTarget("vkpl", "$o:$id:${uri.getQueryParameter("access_hash").orEmpty()}", "Плейлист ВК")
        }
        return null
    }

    private fun parseSoundCloud(p: List<String>): LinkTarget? = when {
        p.size >= 3 && p[1] == "sets" -> LinkTarget("scpl", "${p[0]}/sets/${p[2]}", "Плейлист SoundCloud")
        p.size >= 2 -> LinkTarget("sctrack", "${p[0]}/${p[1]}", "Трек")
        else -> null
    }

    /** Ссылка на список треков, если у него есть адрес в сервисе. */
    fun shareUrl(kind: String, arg: String): String? = when (kind) {
        "yapl" -> arg.split(':').let { "https://music.yandex.ru/users/${it[0]}/playlists/${it[1]}" }
        "yaalbum" -> "https://music.yandex.ru/album/$arg"
        "yauuid" -> "https://music.yandex.ru/playlists/$arg"
        "ytpl" -> "https://music.youtube.com/playlist?list=${arg.removePrefix("VL")}"
        "scpl" -> "https://soundcloud.com/$arg"
        "vkpl" -> arg.split(':').let { p ->
            "https://vk.com/music/playlist/${p[0]}_${p[1]}" + (p.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: "")
        }
        else -> null
    }

    /** Ссылка на трек в его сервисе (файл с телефона — без ссылки). */
    fun trackUrl(t: Track): String? = when (t.source) {
        Source.YANDEX -> t.sourceId.split(':').let { p ->
            if (p.size == 2) "https://music.yandex.ru/album/${p[1]}/track/${p[0]}" else "https://music.yandex.ru/track/${p[0]}"
        }
        Source.YOUTUBE -> "https://music.youtube.com/watch?v=${t.sourceId}"
        Source.SOUNDCLOUD -> "https://soundcloud.com/${t.sourceId}"
        Source.VK, Source.LOCAL -> null
    }

    /** Текстом — для своих плейлистов, у которых нет адреса: «Исполнитель — Название» построчно. */
    fun asText(title: String, tracks: List<Track>): String = buildString {
        appendLine(title)
        appendLine()
        tracks.forEachIndexed { i, t -> appendLine("${i + 1}. ${t.artists} — ${t.title}") }
        appendLine()
        append("Собрано в Unison")
    }
}
