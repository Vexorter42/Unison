package com.musichub

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.musichub.youtube.NewPipeDownloader
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        NewPipe.init(NewPipeDownloader(graph.router.callFactory), Localization("ru", "RU"), ContentCountry("RU"))
        if (graph.settings.current.yandexLoggedIn) {
            graph.scope.launch { runCatching { graph.yandex.refreshLikes() } }
        }
    }

    /** Обложки грузятся тем же маршрутом, что и их сервис (обложки YouTube — через прокси и т.д.). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.router.callFactory })) }
        .build()

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
