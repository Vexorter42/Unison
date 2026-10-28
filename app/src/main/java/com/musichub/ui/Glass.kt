package com.musichub.ui

import com.musichub.util.hiResCover
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/*
 * «Стекло» в духе Liner: цвет экрану даёт обложка, а вся обвязка — нейтральные
 * полупрозрачные белые подложки поверх тёмного фона.
 */

fun glassFill(strong: Boolean = false): Color = Color.White.copy(alpha = if (strong) 0.16f else 0.09f)

/** Размытая обложка на весь фон + затемнение, чтобы текст читался. */
@Composable
fun CoverBackdrop(url: String?, modifier: Modifier = Modifier, strength: Float = 1f) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(
                model = hiResCover(url),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(90.dp) else Modifier),
                alpha = if (Build.VERSION.SDK_INT >= 31) 0.9f * strength else 0.35f * strength,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = 0.35f), bg.copy(alpha = 0.65f), bg)),
            ),
        )
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, size: Dp = 40.dp, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(glassFill()).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** Главная светлая кнопка («Слушать»), как Play all в Liner. */
@Composable
fun PrimaryPill(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    val bg = Color(0xFFE9E9EE)
    val fg = Color(0xFF0D0D10)
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f) else glassFill())
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Маленькая подпись-«таблетка»: «24 трека · 1 ч 12 мин». */
@Composable
fun MetaChip(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(glassFill()).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Сгенерированная обложка для виртуальных коллекций (Мне нравится, Скачанное…): тёмный градиент + глиф. */
@Composable
fun GeneratedCover(icon: ImageVector, hue: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.radialGradient(listOf(hue.copy(alpha = 0.85f), hue.copy(alpha = 0.35f), Color(0xFF101014)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(56.dp))
        content()
    }
}
