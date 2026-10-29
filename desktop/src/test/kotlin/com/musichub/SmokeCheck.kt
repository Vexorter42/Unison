package com.musichub

import com.musichub.data.model.Source
import com.musichub.desktop.AppGraph
import com.musichub.desktop.MediaKeys
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import com.musichub.youtube.NewPipeDownloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Живая проверка без интерфейса: поиск, склейка, воспроизведение через VLC (и Моя волна, если есть вход в Яндекс).
 * Запуск: ./gradlew :desktop:smokeCheck -Pquery="Кино Группа крови"
 */
fun main(args: Array<String>): Unit = runBlocking {
    val query = args.firstOrNull() ?: "Кино Группа крови"
    val graph = AppGraph()
    NewPipe.init(NewPipeDownloader(graph.http), Localization("ru", "RU"), ContentCountry("RU"))
    val engine = graph.engine ?: error("VLC не найден")
    val s = graph.settings.current
    println("Яндекс: ${if (s.yandexLoggedIn) "вход ${s.yandexLogin}" else "без входа"}")

    val yt = runCatching { graph.youtube.search(query) }
    println("YouTube: ${yt.getOrNull()?.size ?: "ошибка ${yt.exceptionOrNull()?.message}"}")
    val ya = runCatching { graph.yandex.search(query) }
    println("Яндекс: ${ya.getOrNull()?.size ?: "ошибка ${ya.exceptionOrNull()?.message}"}")
    val merged = graph.search.merge(ya.getOrDefault(emptyList()), yt.getOrDefault(emptyList()))
    println("Склеено: ${merged.size}, с копиями: ${merged.count { it.alternates.isNotEmpty() }}")
    merged.take(5).forEach { println("  [${it.source.short}${it.alternates.joinToString("") { a -> "+" + a.source.short }}] ${it.artists} — ${it.title}") }

    suspend fun playAndCheck(label: String, expectedKey: String?, start: () -> Unit) {
        start()
        val ok = withTimeoutOrNull(25_000) {
            engine.state.first { st ->
                st.isPlaying && st.positionMs in 1500..20_000 && (expectedKey == null || st.track?.key == expectedKey) &&
                    (expectedKey != null || st.waveMode)
            }
        }
        val st = engine.state.value
        println("$label: ${if (ok != null) "ИГРАЕТ" else "НЕ ИГРАЕТ"} — ${st.track?.artists} — ${st.track?.title}, " +
            "${st.positionMs} из ${st.durationMs} мс${if (st.viaFallback) " (из замены)" else ""}")
    }

    yt.getOrNull()?.firstOrNull()?.let { t -> playAndCheck("YouTube", t.key) { engine.playTracks(listOf(t)) } }
    engine.seekTo(60_000)
    delay(3000)
    println("Перемотка на 1:00 → ${engine.state.value.positionMs} мс")

    // Медиаклавиша Play/Pause: хоткей держит этот процесс, нажатие приходит ему
    val keys = MediaKeys(onPlayPause = engine::togglePlay, onNext = engine::next, onPrevious = engine::previous, onStop = {})
    keys.start()
    delay(500)
    fun pressPlayPause() {
        Keyboard.lib.keybd_event(0xB3.toByte(), 0, 1, BaseTSD.ULONG_PTR(0))
        Keyboard.lib.keybd_event(0xB3.toByte(), 0, 3, BaseTSD.ULONG_PTR(0))
    }
    pressPlayPause()
    delay(1500)
    val paused = !engine.state.value.isPlaying
    pressPlayPause()
    delay(1500)
    println("Медиаклавиша: пауза ${if (paused) "ок" else "НЕ сработала"}, продолжение ${if (engine.state.value.isPlaying) "ок" else "НЕ сработало"}")
    keys.stop()
    ya.getOrNull()?.firstOrNull { it.source == Source.YANDEX }?.let { t -> playAndCheck("Яндекс", t.key) { engine.playTracks(listOf(t)) } }
    if (s.yandexLoggedIn) playAndCheck("Моя волна", null) { engine.startWave() }
    graph.lyrics.get(engine.state.value.track ?: merged.first())?.let { println("Текст: ${it.synced?.size ?: 0} синхр. строк") }
    engine.release()
    kotlin.system.exitProcess(0)
}

@Suppress("FunctionName")
private interface Keyboard : StdCallLibrary {
    fun keybd_event(vk: Byte, scan: Byte, flags: Int, extra: BaseTSD.ULONG_PTR)

    companion object {
        val lib: Keyboard = Native.load("user32", Keyboard::class.java)
    }
}
