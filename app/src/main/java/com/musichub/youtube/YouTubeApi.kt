package com.musichub.youtube

import com.musichub.data.Quality
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import kotlin.math.abs

/** YouTube / YouTube Music через NewPipe Extractor (без аккаунта Google). */
class YouTubeApi {

    private val service get() = ServiceList.YouTube

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun search(query: String): List<Track> = io { searchBlocking(query) }

    fun searchBlocking(query: String): List<Track> {
        val extractor = service.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        extractor.fetchPage()
        return extractor.initialPage.items.mapNotNull { it.toTrack() }
    }

    /** Ищет на YouTube тот же трек, что и [target] (для замены и микса). */
    fun findMatchBlocking(target: Track): Track? {
        val q = TrackMatcher.splitYoutube(target)
        val candidates = runCatching { searchBlocking("${q.artists} ${q.title}") }.getOrDefault(emptyList())
        return TrackMatcher.best(q, candidates, threshold = 0.6)
    }

    fun streamUrlBlocking(videoId: String, quality: Quality): String {
        val info = StreamInfo.getInfo(service, watchUrl(videoId))
        val streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (streams.isEmpty()) throw IOException("YouTube: нет аудиопотока")
        val sorted = streams.sortedBy { it.averageBitrate }
        val pick = when (quality) {
            Quality.HIGH -> sorted.last()
            Quality.LOW -> sorted.first()
            Quality.NORMAL -> sorted.minBy { abs(it.averageBitrate - 128) }
        }
        return pick.content
    }

    /** Похожие треки (для подмешивания в Мою волну). */
    suspend fun related(videoId: String): List<Track> = io {
        StreamInfo.getInfo(service, watchUrl(videoId)).relatedItems
            .filter { it is StreamInfoItem && it.duration in 60..900 }
            .mapNotNull { it.toTrack() }
    }

    /** Публичный плейлист YouTube / YouTube Music по ссылке. */
    suspend fun playlist(url: String, limit: Int = 1000): Pair<String, List<Track>> = io {
        val normalized = url.trim().replace("music.youtube.com", "www.youtube.com")
        val info = PlaylistInfo.getInfo(service, normalized)
        val items = info.relatedItems.toMutableList<InfoItem>()
        var next: Page? = info.nextPage
        while (Page.isValid(next) && items.size < limit) {
            val page = PlaylistInfo.getMoreItems(service, normalized, next)
            items += page.items
            next = page.nextPage
        }
        info.name to items.mapNotNull { it.toTrack() }
    }

    private fun InfoItem.toTrack(): Track? {
        if (this !is StreamInfoItem) return null
        val id = runCatching { service.streamLHFactory.getId(url) }.getOrNull() ?: return null
        return Track(
            source = Source.YOUTUBE,
            sourceId = id,
            title = name,
            artists = uploaderName?.removeSuffix(" - Topic")?.ifBlank { null } ?: "YouTube",
            durationMs = duration.coerceAtLeast(0) * 1000,
            coverUrl = bestThumb(thumbnails),
        )
    }

    private fun bestThumb(images: List<Image>): String? = images.maxByOrNull { it.height }?.url

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    companion object {
        /**
         * googlevideo проверяет, что User-Agent совпадает с клиентом, для которого выдана ссылка
         * (параметр c=ANDROID / c=IOS). Ставится в OkHttp-клиент плеера.
         */
        val streamUserAgentInterceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            if (!req.url.host.endsWith("googlevideo.com")) return@Interceptor chain.proceed(req)
            val ua = when {
                YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
                YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
                else -> NewPipeDownloader.USER_AGENT
            }
            chain.proceed(req.newBuilder().header("User-Agent", ua).build())
        }
    }
}
