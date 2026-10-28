package com.musichub.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette as AndroidPalette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Палитра из обложки трека — как в Liner: цвет экрану даёт музыка, которая играет. */
object CoverColors {

    private val memo = ConcurrentHashMap<String, Palette>()
    private val noColors = Palette("", Color.Unspecified, Color.Unspecified, Color.Unspecified)

    /** null — обложка почти бесцветная (чёрно-белая), лучше оставить выбранную палитру. */
    suspend fun extract(context: Context, url: String): Palette? = withContext(Dispatchers.IO) {
        memo[url]?.let { return@withContext it.takeIf { p -> p !== noColors } }
        val request = ImageRequest.Builder(context).data(url).size(160).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
            ?: return@withContext null
        val p = AndroidPalette.from(bitmap).maximumColorCount(16).generate()

        val swatches = p.swatches
        val colorful = swatches.any { it.hsl[1] > 0.18f && it.population > 20 }
        if (!colorful) {
            memo[url] = noColors
            return@withContext null
        }
        val a = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.dominantSwatch
        val b = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        val c = p.lightVibrantSwatch ?: p.lightMutedSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        val palette = Palette(
            name = "Обложка",
            a = tune(a?.rgb, lightness = 0.38f),
            b = tune(b?.rgb, lightness = 0.5f),
            c = tune(c?.rgb, lightness = 0.62f),
        )
        memo[url] = palette
        palette
    }

    /** Приводим цвет к «сочной» насыщенности и нужной светлоте — чтобы на тёмном фоне не было ни серости, ни кислоты. */
    private fun tune(rgb: Int?, lightness: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb ?: Color.Gray.toArgb(), hsl)
        hsl[1] = hsl[1].coerceIn(0.45f, 0.85f)
        hsl[2] = lightness
        return Color(ColorUtils.HSLToColor(hsl))
    }
}
