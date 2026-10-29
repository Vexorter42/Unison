package com.musichub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.desktop.AppGraph
import com.musichub.playback.PlaybackEngine
import com.musichub.ui.theme.LocalPalette
import com.musichub.util.formatTime
import com.musichub.util.hiResCover

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph не передан") }
val LocalEngine = staticCompositionLocalOf<PlaybackEngine> { error("Плеер не передан") }

/*
 * «Стекло» в духе Liner: цвет экрану даёт обложка, а вся обвязка — нейтральные
 * полупрозрачные белые подложки поверх тёмного фона.
 */

/** Enter в поле ввода (на десктопе у однострочного поля нет «действия клавиатуры»). */
fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
        action()
        true
    } else {
        false
    }
}

fun glassFill(strong: Boolean = false): Color = Color.White.copy(alpha = if (strong) 0.14f else 0.07f)

/** Размытая обложка на весь фон + затемнение, чтобы текст читался. */
@Composable
fun CoverBackdrop(url: String?, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val palette = LocalPalette.current
    val image = rememberImage(hiResCover(url))
    Box(modifier.fillMaxSize().background(bg)) {
        // Мягкое цветное свечение из палитры — даже когда обложки нет
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(palette.a.copy(alpha = 0.35f), Color.Transparent), radius = 1400f),
            ),
        )
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize().blur(110.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(bg.copy(alpha = 0.25f), bg.copy(alpha = 0.6f), bg.copy(alpha = 0.92f))),
            ),
        )
    }
}

@Composable
fun Cover(url: String?, size: Dp = Dp.Unspecified, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val image = rememberImage(hiResCover(url))
    Box(
        modifier
            .then(if (size != Dp.Unspecified) Modifier.size(size) else Modifier)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Сгенерированная обложка для виртуальных коллекций (Мне нравится, Любимое…): тёмный градиент + глиф. */
@Composable
fun GeneratedCover(icon: ImageVector, hue: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.radialGradient(listOf(hue.copy(alpha = 0.85f), hue.copy(alpha = 0.35f), Color(0xFF101014)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(48.dp))
        content()
    }
}

@Composable
fun SourceBadge(source: Source, highlighted: Boolean = true) {
    val color = when (source) {
        Source.YANDEX -> Color(0xFFFFCC33)
        Source.VK -> Color(0xFF5B9BFF)
        Source.YOUTUBE -> Color(0xFFFF5A5A)
        Source.LOCAL -> MaterialTheme.colorScheme.secondary
    }
    Text(
        source.short,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = if (highlighted) color else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (highlighted) color.copy(alpha = 0.16f) else glassFill())
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun SourceBadges(track: Track) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        SourceBadge(track.source)
        track.alternates.forEach { SourceBadge(it.source, highlighted = false) }
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(if (filled || hovered) glassFill(strong = hovered) else Color.Transparent)
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize)) }
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
fun GlassButton(text: String, icon: ImageVector? = null, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(glassFill(strong = true))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f) else glassFill())
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(vertical = 8.dp))
}

/** Строка трека: обложка, название, источники, длительность, сердечко и меню. */
@Composable
fun TrackRow(
    track: Track,
    index: Int?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** Узкая строка для боковой панели: без альбома, сердечка и длительности. */
    compact: Boolean = false,
) {
    val graph = LocalGraph.current
    val engine = LocalEngine.current
    val playing by engine.state.collectAsState()
    val favKeys by graph.favorites.keys.collectAsState()
    val isCurrent = playing.track?.key == track.key
    val liked = track.key in favKeys
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) glassFill(strong = true) else if (hovered) glassFill() else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) Icon(Icons.Rounded.GraphicEq, null, tint = accent, modifier = Modifier.size(18.dp))
                else Text("${index + 1}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Cover(track.coverUrl, size = 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadges(track)
                Spacer(Modifier.width(6.dp))
                Text(
                    track.artists,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (track.album != null && !compact) {
            Text(
                track.album,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.6f).padding(end = 12.dp),
            )
        }
        if (!compact) {
            GlassIconButton(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Убрать из «Любимого»" else "В «Любимое»",
                onClick = { engine.toggleLike(track) },
                size = 32.dp,
                iconSize = 18.dp,
                tint = if (liked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (track.durationMs > 0) formatTime(track.durationMs) else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp).padding(start = 6.dp),
            )
        }
        Box {
            GlassIconButton(Icons.Rounded.MoreHoriz, "Ещё", onClick = { menu = true }, size = 32.dp, iconSize = 18.dp)
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Играть следующим") }, onClick = { menu = false; engine.playNext(track) })
                DropdownMenuItem(text = { Text("Добавить в очередь") }, onClick = { menu = false; engine.enqueue(track) })
                DropdownMenuItem(text = { Text("Радио по треку") }, onClick = { menu = false; engine.startRadio(track) })
            }
        }
    }
}
