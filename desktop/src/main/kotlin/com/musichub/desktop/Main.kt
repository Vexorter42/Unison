package com.musichub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.musichub.ui.GlassButton
import com.musichub.ui.LocalEngine
import com.musichub.ui.LocalGraph
import com.musichub.ui.UnisonApp
import com.musichub.ui.screens.openInBrowser
import com.musichub.youtube.NewPipeDownloader
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

fun main() {
    val graph = AppGraph()
    NewPipe.init(NewPipeDownloader(graph.http), Localization("ru", "RU"), ContentCountry("RU"))
    if (graph.settings.current.yandexLoggedIn) {
        graph.scope.launch { runCatching { graph.yandex.refreshLikes() } }
    }
    val engine = graph.engine
    val mediaKeys = engine?.let {
        MediaKeys(onPlayPause = it::togglePlay, onNext = it::next, onPrevious = it::previous, onStop = { it.togglePlay() })
    }
    mediaKeys?.start()

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("unison.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }

    application {
        val windowState = rememberWindowState(size = DpSize(1320.dp, 860.dp))
        Window(
            onCloseRequest = {
                mediaKeys?.stop()
                engine?.release()
                exitApplication()
            },
            title = "Unison",
            icon = icon,
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(1000, 640)
            LaunchedEffect(Unit) { WindowsTitleBar.useDarkMode(window) }
            CompositionLocalProvider(LocalGraph provides graph) {
                if (engine != null) {
                    CompositionLocalProvider(LocalEngine provides engine) { UnisonApp() }
                } else {
                    NoVlcScreen()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NoVlcScreen() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D0D10)).padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Не найден VLC", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Unison играет музыку через libVLC. Установите VLC media player (64-bit) и перезапустите приложение.",
                color = Color(0xFF9A9AA6),
            )
            GlassButton("Скачать VLC", onClick = { openInBrowser("https://www.videolan.org/vlc/") })
        }
    }
}
