package com.musichub.desktop

import com.musichub.data.Favorites
import com.musichub.data.SettingsRepository
import com.musichub.lyrics.LyricsRepository
import com.musichub.net.FallbackDns
import com.musichub.playback.PlaybackEngine
import com.musichub.playback.StreamProxy
import com.musichub.playback.StreamResolver
import com.musichub.playback.Vlc
import com.musichub.search.SearchRepository
import com.musichub.ui.ImageLoader
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import com.musichub.youtube.YtMusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Ручной DI: все синглтоны приложения в одном месте. */
class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    /** DNS с запасным DoH: VPN вроде Happ может ломать системный DNS только для приложений. */
    val dns = FallbackDns()

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(YouTubeApi.streamUserAgentInterceptor)
        .build()

    /** Для потоков: читать можно долго (пауза), а VLC сам решает, когда закрыть соединение. */
    private val streamHttp: OkHttpClient = http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build()

    val settings = SettingsRepository()
    val yandex = YandexApi(http, settings)
    val vk = VkApi(http, settings)
    val youtube = YouTubeApi()
    val ytMusic = YtMusicApi(http) { settings.current.ytCookie }
    val lyrics = LyricsRepository(http)
    val search = SearchRepository(yandex, vk, youtube, settings)
    val favorites = Favorites(yandex, youtube, ytMusic)
    val images = ImageLoader(http)

    private val resolver = StreamResolver(settings, yandex, vk, youtube)
    private val proxy = StreamProxy(streamHttp)

    /** null — не нашёлся VLC: интерфейс покажет, что делать. */
    val engine: PlaybackEngine? = Vlc.createFactory()?.let { factory ->
        PlaybackEngine(factory, settings, resolver, proxy, yandex, youtube, favorites)
    }
}
