package com.musichub.soundcloud

import com.musichub.data.model.Source
import com.musichub.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/**
 * SoundCloud через NewPipe Extractor (без аккаунта). Звук отдаётся потоком HLS,
 * поэтому такие треки играют только онлайн. Официальные релизы лейблов — Go+,
 * бесплатно это лишь 30-секундные отрывки: их в выдачу не берём.
 */
class SoundCloudApi {

    private val service get() = ServiceList.SoundCloud

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun search(query: String): List<Track> = io {
        val ex = service.getSearchExtractor(query, listOf("tracks"), "")
        ex.fetchPage()
        ex.initialPage.items.mapNotNull { it.toTrack() }
    }

    /** Трек по ссылке soundcloud.com/<автор>/<трек>. */
    suspend fun track(path: String): Track? = io {
        val ex = service.getStreamExtractor(url(path))
        ex.fetchPage()
        Track(
            source = Source.SOUNDCLOUD,
            sourceId = path,
            title = ex.name,
            artists = ex.uploaderName.ifBlank { "SoundCloud" },
            durationMs = ex.length * 1000,
            coverUrl = ex.thumbnails.maxByOrNull { it.height }?.url,
        )
    }

    /** Плейлист (sets) по ссылке soundcloud.com/<автор>/sets/<имя>. */
    suspend fun playlist(path: String, limit: Int = 500): List<Track> = io {
        val info = PlaylistInfo.getInfo(service, url(path))
        val items = info.relatedItems.toMutableList<InfoItem>()
        var next: Page? = info.nextPage
        while (Page.isValid(next) && items.size < limit) {
            val page = PlaylistInfo.getMoreItems(service, url(path), next)
            items += page.items
            next = page.nextPage
        }
        items.mapNotNull { it.toTrack() }
    }

    /** Ссылка на HLS-плейлист трека (плеер сам догружает его части). */
    fun streamUrlBlocking(path: String): String {
        val ex = service.getStreamExtractor(url(path))
        ex.fetchPage()
        val streams = ex.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.HLS }
        return streams.maxByOrNull { it.averageBitrate }?.content
            ?: throw IOException("SoundCloud: трек недоступен (платный Go+ или закрыт в вашем регионе)")
    }

    private fun InfoItem.toTrack(): Track? {
        if (this !is StreamInfoItem) return null
        if (duration in 29..31) return null // отрывок Go+
        val path = url.substringAfter("soundcloud.com/", "").substringBefore('?').trim('/').ifEmpty { return null }
        return Track(
            source = Source.SOUNDCLOUD,
            sourceId = path,
            title = name,
            artists = uploaderName?.ifBlank { null } ?: "SoundCloud",
            durationMs = duration.coerceAtLeast(0) * 1000,
            coverUrl = thumbnails.maxByOrNull { it.height }?.url,
        )
    }

    companion object {
        fun url(path: String) = "https://soundcloud.com/$path"
    }
}
