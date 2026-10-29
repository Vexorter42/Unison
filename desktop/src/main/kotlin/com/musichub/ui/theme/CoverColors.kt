package com.musichub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Палитра из обложки трека — как в Liner: цвет экрану даёт музыка, которая играет. */
object CoverColors {

    private val memo = ConcurrentHashMap<String, Palette>()
    private val noColors = Palette("", Color.Unspecified, Color.Unspecified, Color.Unspecified)
    private const val BUCKETS = 18

    /** null — обложка почти бесцветная (чёрно-белая), лучше оставить выбранную палитру. */
    suspend fun extract(key: String, image: ImageBitmap): Palette? = withContext(Dispatchers.Default) {
        memo[key]?.let { return@withContext it.takeIf { p -> p !== noColors } }
        val pixels = image.toPixelMap()
        val step = max(1, min(pixels.width, pixels.height) / 48)
        // Гистограмма оттенков, вес — насыщенность (серые пиксели почти не голосуют)
        val weight = DoubleArray(BUCKETS)
        val sinSum = DoubleArray(BUCKETS)
        val cosSum = DoubleArray(BUCKETS)
        var total = 0
        var colorful = 0
        for (y in 0 until pixels.height step step) for (x in 0 until pixels.width step step) {
            val c = pixels[x, y]
            val (h, s, l) = hsl(c.red, c.green, c.blue)
            total++
            if (s < 0.18f || l < 0.08f || l > 0.92f) continue
            colorful++
            val w = s * (1 - abs(l - 0.5f) * 1.4f).toDouble()
            val bucket = ((h / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            val rad = Math.toRadians(h.toDouble())
            weight[bucket] += w
            sinSum[bucket] += sin(rad) * w
            cosSum[bucket] += cos(rad) * w
        }
        if (total == 0 || colorful.toDouble() / total < 0.06) {
            memo[key] = noColors
            return@withContext null
        }
        val hues = weight.indices.sortedByDescending { weight[it] }.filter { weight[it] > 0 }
            .map { ((Math.toDegrees(atan2(sinSum[it], cosSum[it])) + 360) % 360).toFloat() }
        val first = hues[0]
        // Второй и третий цвета — заметно другие оттенки, если они есть, иначе соседние по кругу
        val second = hues.drop(1).firstOrNull { hueDistance(it, first) > 25 } ?: ((first + 28) % 360)
        val third = hues.drop(1).firstOrNull { hueDistance(it, first) > 25 && hueDistance(it, second) > 25 } ?: ((second + 24) % 360)
        val palette = Palette(
            name = "Обложка",
            a = Color.hsl(first, 0.7f, 0.38f),
            b = Color.hsl(second, 0.7f, 0.5f),
            c = Color.hsl(third, 0.65f, 0.62f),
        )
        memo[key] = palette
        palette
    }

    private fun hueDistance(a: Float, b: Float): Float = min(abs(a - b), 360 - abs(a - b))

    private fun hsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return Triple(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return Triple(h.coerceIn(0f, 359.9f), s, l)
    }
}
