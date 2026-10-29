package com.musichub

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.musichub.desktop.AppGraph
import com.musichub.ui.LocalEngine
import com.musichub.ui.LocalGraph
import com.musichub.ui.UnisonApp
import com.musichub.youtube.NewPipeDownloader
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File

/**
 * Проверка интерфейса без окна: настоящий UnisonApp, ввод запроса, клик по первому результату,
 * ожидание воспроизведения, панель текста. Скриншоты — в папку из первого аргумента.
 */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "ui-check" }).apply { mkdirs() }
    val query = args.getOrElse(1) { "Кино Группа крови" }
    val graph = AppGraph()
    NewPipe.init(NewPipeDownloader(graph.http), Localization("ru", "RU"), ContentCountry("RU"))
    val engine = graph.engine ?: error("VLC не найден")

    runDesktopComposeUiTest(width = 1320, height = 820) {
        setContent {
            CompositionLocalProvider(LocalGraph provides graph, LocalEngine provides engine) { UnisonApp() }
        }
        fun SemanticsNodeInteractionsProvider.shot(name: String) {
            val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
            File(out, "$name.png").writeBytes(png.bytes)
            println("Скриншот: ${File(out, "$name.png")}")
        }
        shot("1-home")

        onNodeWithText("Поиск").performClick()
        onNode(hasSetTextAction()).performTextInput(query)
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        println("Результатов на экране: ${onAllNodesWithText("Группа крови", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size}")
        onAllNodesWithText("1", useUnmergedTree = true)[0].performClick()
        waitUntil(timeoutMillis = 30_000) { engine.state.value.let { it.isPlaying && it.positionMs > 2000 } }
        val st = engine.state.value
        println("Играет: ${st.track?.source} ${st.track?.artists} — ${st.track?.title}, ${st.positionMs} мс${if (st.viaFallback) " (из замены)" else ""}")
        shot("2-search-playing")

        onNodeWithContentDescription("Текст песни").performClick()
        waitUntil(timeoutMillis = 20_000) { engine.state.value.positionMs > 15_000 }
        shot("3-lyrics")
        onNodeWithContentDescription("Очередь").performClick()
        onNodeWithText("Медиатека").performClick()
        waitForIdle()
        shot("4-library-queue")
        onNodeWithText("Настройки").performClick()
        waitForIdle()
        shot("5-settings")
    }
    engine.release()
    kotlin.system.exitProcess(0)
}
