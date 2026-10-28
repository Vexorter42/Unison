package com.musichub

import android.app.Application
import androidx.room.Room
import com.musichub.data.Backup
import com.musichub.data.Favorites
import com.musichub.data.LibraryRepository
import com.musichub.data.SettingsRepository
import com.musichub.data.TrackSources
import com.musichub.data.db.AppDatabase
import com.musichub.links.LinkTarget
import com.musichub.local.LocalMusic
import com.musichub.lyrics.LyricsRepository
import com.musichub.net.FallbackDns
import com.musichub.net.NetworkRouter
import com.musichub.playback.EqualizerController
import com.musichub.playback.PlaybackEngine
import com.musichub.search.SearchRepository
import com.musichub.transfer.TransferRepository
import com.musichub.update.Updater
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import com.musichub.youtube.YtMusicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Ручной DI: все синглтоны приложения в одном месте. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** DNS с запасным DoH: VPN вроде Happ может ломать системный DNS только для приложений. */
    val dns = FallbackDns()

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(YouTubeApi.streamUserAgentInterceptor)
        .build()

    val settings = SettingsRepository(app)

    /** Все запросы идут через роутер: он решает, какой сервис пускать через прокси, а какой мимо VPN. */
    val router = NetworkRouter(app, settings, http, dns)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "musichub.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration(true)
        .build()

    val yandex = YandexApi(router.callFactory, settings)
    val vk = VkApi(router.callFactory, settings)
    val youtube = YouTubeApi()
    val ytMusic = YtMusicApi(router.callFactory) { settings.current.ytCookie }
    val localMusic = LocalMusic(app)
    val lyrics = LyricsRepository(http)
    val search = SearchRepository(yandex, vk, youtube, localMusic, settings)
    val library = LibraryRepository(db)
    val favorites = Favorites(this)
    val sources = TrackSources(this)
    val backup = Backup(app, this)

    /** Ссылка, пришедшая через «Поделиться» / «Открыть в», — экран откроет её при первой возможности. */
    val pendingLink = MutableStateFlow<LinkTarget?>(null)
    val transfer = TransferRepository(this)
    val equalizer = EqualizerController(app)
    val engine: PlaybackEngine by lazy { PlaybackEngine(app, this) }
    val updater = Updater(app, http, scope)
}
