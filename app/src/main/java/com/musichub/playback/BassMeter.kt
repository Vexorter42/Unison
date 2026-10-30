package com.musichub.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Сила басов 0..1 для сферы Моей волны. Звук не меняет: ExoPlayer отдаёт копию PCM до громкости,
 * поэтому сфера не зависит от громкости. Разрешение на микрофон (Visualizer API) не нужно.
 *
 * Звук попадает сюда раньше, чем в динамик: каждый кусок помечается временем, когда он зазвучит
 * (пачки идут подряд друг за другом), и интерфейс берёт уровень того куска, что звучит сейчас.
 */
@OptIn(UnstableApi::class)
class BassMeter : TeeAudioProcessor.AudioBufferSink {

    /** Выключено — анализ не идёт (настройка «Живая сфера» или играет не волна). */
    @Volatile
    var enabled: Boolean = true

    private var rate = 44100
    private var channels = 2
    private var pcm16 = true
    private var lowPassK = lowPass(44100)
    private var lowPass = 0.0
    private var peak = 0.02
    private var smooth = 0f
    /** Когда зазвучит следующий кусок: ExoPlayer отдаёт звук пачками, а уровни должны идти в такт. */
    private var nextTime = 0L

    // Кольцо (время, уровень): пишет поток воспроизведения, читает интерфейс
    private val times = LongArray(RING)
    private val levels = FloatArray(RING)
    @Volatile private var head = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz.takeIf { it > 0 } ?: 44100
        channels = channelCount.coerceAtLeast(1)
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        lowPassK = lowPass(rate)
        lowPass = 0.0
        smooth = 0f
        nextTime = 0L
        push(SystemClock.elapsedRealtime(), 0f)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || !pcm16) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val chunk = (rate / 100).coerceAtLeast(1)
        var left = b.remaining() / (2 * channels)
        nextTime = max(nextTime, SystemClock.elapsedRealtime())
        // Кусками по 10 мс — как блоки у ПК-версии, иначе сфера дёргалась бы рывками раз в пачку
        while (left > 0) {
            val frames = minOf(chunk, left)
            measure(b, frames)
            push(nextTime, smooth)
            nextTime += frames * 1000L / rate
            left -= frames
        }
    }

    private fun measure(b: ByteBuffer, frames: Int) {
        var energy = 0.0
        val k = lowPassK
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += b.short.toInt()
            val x = sum / (32768.0 * channels)
            lowPass += k * (x - lowPass)
            energy += lowPass * lowPass
        }
        val rms = sqrt(energy / frames)
        peak = max(rms, peak * 0.998).coerceAtLeast(0.003)
        val target = (rms / peak).coerceIn(0.0, 1.0).let { it * it }.toFloat()
        // Быстрая атака, медленный спад — удар баса виден, но картинка не дёргается
        smooth += (target - smooth) * if (target > smooth) 0.55f else 0.12f
    }

    private fun push(time: Long, v: Float) {
        val i = (head + 1) % RING
        times[i] = time
        levels[i] = v
        head = i
    }

    /** Уровень куска, который сейчас звучит ([DELAY_MS] — задержка вывода звука). */
    fun level(): Float {
        if (!enabled) return 0f
        val cutoff = SystemClock.elapsedRealtime() - DELAY_MS
        var i = head
        repeat(RING) {
            if (times[i] != 0L && times[i] <= cutoff) {
                // Давно не было звука (пауза) — тишина
                return if (cutoff - times[i] > 400) 0f else levels[i]
            }
            i = (i - 1 + RING) % RING
        }
        return 0f
    }

    private companion object {
        const val RING = 512
        const val DELAY_MS = 60L
        fun lowPass(rate: Int) = 1.0 - exp(-2.0 * Math.PI * 120.0 / rate)
    }
}
