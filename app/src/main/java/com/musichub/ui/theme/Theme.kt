package com.musichub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.musichub.App

/** Цветовая палитра: три цвета градиента. Из них строятся фон, «Моя волна», переключатели и акцент. */
data class Palette(val name: String, val a: Color, val b: Color, val c: Color) {
    val gradient: Brush get() = Brush.linearGradient(listOf(a, b, c))
    val horizontal: Brush get() = Brush.horizontalGradient(listOf(a, b))
    /** Акцент для текста и активных состояний — светлее, чтобы читался на тёмном фоне. */
    val accent: Color get() = lerp(b, Color.White, 0.3f)
}

val Palettes = listOf(
    Palette("Фиолет", Color(0xFF6D3BF2), Color(0xFFC13BE0), Color(0xFFFF7A45)),
    Palette("Аврора", Color(0xFF00C9A7), Color(0xFF3AA0FF), Color(0xFF7B61FF)),
    Palette("Закат", Color(0xFFFF5F6D), Color(0xFFFF9A5A), Color(0xFFFFC371)),
    Palette("Океан", Color(0xFF0052D4), Color(0xFF4364F7), Color(0xFF6FB1FC)),
    Palette("Неон", Color(0xFFFF2EC8), Color(0xFF7A2BFF), Color(0xFF00E5FF)),
    Palette("Мята", Color(0xFF11998E), Color(0xFF38EF7D), Color(0xFFB6F35C)),
    Palette("Огонь", Color(0xFFE1251B), Color(0xFFF5781F), Color(0xFFFFC93C)),
    Palette("Сакура", Color(0xFFFF8FA3), Color(0xFFF7B2C8), Color(0xFFA18CD1)),
    Palette("Полночь", Color(0xFF1F3B73), Color(0xFF3D6CB9), Color(0xFF7FA7E0)),
    Palette("Арктика", Color(0xFF5EE7DF), Color(0xFF9FACE6), Color(0xFFB490CA)),
    Palette("Графит", Color(0xFF3C3C46), Color(0xFF8A8A99), Color(0xFFD9D9E2)),
)

val LocalPalette = compositionLocalOf { Palettes[0] }

private fun darkScheme(p: Palette) = darkColorScheme(
    primary = p.accent,
    onPrimary = Color(0xFF0D0D10),
    primaryContainer = lerp(p.a, Color(0xFF0D0D10), 0.6f),
    secondary = lerp(p.c, Color.White, 0.25f),
    tertiary = Color(0xFFFFB38A),
    background = Color(0xFF0D0D10),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF0D0D10),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1A1A20),
    onSurfaceVariant = Color(0xFF9A9AA6),
    surfaceContainer = Color(0xFF141418),
    surfaceContainerHigh = Color(0xFF1C1C22),
    surfaceContainerHighest = Color(0xFF2A2A33),
    surfaceContainerLow = Color(0xFF111114),
    outline = Color(0xFF34343C),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun MusicHubTheme(content: @Composable () -> Unit) {
    val settings by App.graph.settings.flow.collectAsState()
    val palette = Palettes.getOrElse(settings.palette) { Palettes[0] }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            // Как в Liner — тёмная тема всегда: на ней «стекло» и размытые обложки смотрятся правильно
            colorScheme = darkScheme(palette),
            shapes = AppShapes,
            content = content,
        )
    }
}
