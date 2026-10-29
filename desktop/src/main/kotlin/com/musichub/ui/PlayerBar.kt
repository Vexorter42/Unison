package com.musichub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.playback.RepeatMode
import com.musichub.util.formatTime

enum class SidePanel { NONE, LYRICS, QUEUE }

@Composable
fun PlayerBar(panel: SidePanel, onPanel: (SidePanel) -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val engine = LocalEngine.current
    val s by engine.state.collectAsState()
    val favKeys by graph.favorites.keys.collectAsState()
    val track = s.track
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF16161B).copy(alpha = 0.72f))
            .background(glassFill())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Слева: обложка и название
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Cover(track?.coverUrl, size = 60.dp, corner = 10.dp)
            Column(Modifier.weight(1f, fill = false).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    track?.title ?: "Ничего не играет",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artists ?: "Включите Мою волну или найдите трек",
                    fontSize = 12.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val label = listOfNotNull(s.radioTitle, if (s.viaFallback) "из замены" else null).joinToString(" · ")
                if (label.isNotEmpty()) Text(label, fontSize = 11.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (track != null) {
                val liked = track.key in favKeys
                GlassIconButton(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "Любимое",
                    onClick = { engine.toggleLike() },
                    tint = if (liked) accent else muted,
                )
                if (s.waveMode) GlassIconButton(Icons.Rounded.ThumbDown, "Не нравится", onClick = { engine.dislike() }, tint = muted, iconSize = 18.dp)
            }
        }

        // По центру: управление и прогресс
        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassIconButton(
                    Icons.Rounded.Shuffle, "Перемешать", onClick = { engine.toggleShuffle() },
                    tint = if (s.shuffle) accent else muted, enabled = !s.waveMode,
                )
                GlassIconButton(Icons.Rounded.SkipPrevious, "Предыдущий", onClick = { engine.previous() }, iconSize = 26.dp)
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(onSurface)
                        .then(Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.isBuffering && !s.isPlaying) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.background, strokeWidth = 2.dp)
                    } else {
                        GlassIconButton(
                            if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (s.isPlaying) "Пауза" else "Играть",
                            onClick = { engine.togglePlay() },
                            size = 44.dp,
                            iconSize = 28.dp,
                            tint = MaterialTheme.colorScheme.background,
                        )
                    }
                }
                GlassIconButton(Icons.Rounded.SkipNext, "Следующий", onClick = { engine.next() }, iconSize = 26.dp, enabled = s.hasNext || s.repeat != RepeatMode.OFF)
                GlassIconButton(
                    if (s.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Повтор",
                    onClick = { engine.cycleRepeat() },
                    tint = if (s.repeat != RepeatMode.OFF) accent else muted,
                    enabled = !s.waveMode,
                )
            }
            ProgressRow(s.positionMs, s.durationMs, onSeek = { engine.seekTo(it) })
        }

        // Справа: текст, очередь, громкость
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(
                Icons.Rounded.Lyrics, "Текст песни",
                onClick = { onPanel(if (panel == SidePanel.LYRICS) SidePanel.NONE else SidePanel.LYRICS) },
                tint = if (panel == SidePanel.LYRICS) accent else muted,
            )
            GlassIconButton(
                Icons.AutoMirrored.Rounded.QueueMusic, "Очередь",
                onClick = { onPanel(if (panel == SidePanel.QUEUE) SidePanel.NONE else SidePanel.QUEUE) },
                tint = if (panel == SidePanel.QUEUE) accent else muted,
            )
            Spacer(Modifier.width(8.dp))
            var beforeMute by remember { mutableStateOf(80) }
            GlassIconButton(
                when {
                    s.volume == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                    s.volume < 50 -> Icons.AutoMirrored.Rounded.VolumeDown
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                "Громкость",
                onClick = {
                    if (s.volume > 0) {
                        beforeMute = s.volume
                        engine.setVolume(0)
                    } else {
                        engine.setVolume(beforeMute.coerceAtLeast(10))
                    }
                },
                tint = muted,
            )
            ThinSlider(
                value = s.volume / 100f,
                onValueChange = { engine.setVolume((it * 100).toInt()) },
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

@Composable
private fun ProgressRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            formatTime(dragging?.let { (it * durationMs).toLong() } ?: positionMs),
            fontSize = 11.sp, color = muted, modifier = Modifier.width(44.dp),
        )
        ThinSlider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f),
        )
        Text(formatTime(durationMs), fontSize = 11.sp, color = muted, modifier = Modifier.width(44.dp).padding(start = 8.dp))
    }
}

/** Тонкая полоска как в Liner: при наведении толще и с ручкой; клик и перетаскивание меняют значение. */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val color = MaterialTheme.colorScheme.onSurface
    val update by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    Canvas(
        modifier
            .height(20.dp)
            .hoverable(hover)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { update((it.x / size.width).coerceIn(0f, 1f)); finish() }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { update((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { change, _ -> update((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val h = if (hovered) 6.dp.toPx() else 4.dp.toPx()
        val y = size.height / 2
        val r = CornerRadius(h / 2)
        val v = value.coerceIn(0f, 1f)
        drawRoundRect(color.copy(alpha = 0.18f), Offset(0f, y - h / 2), Size(size.width, h), r)
        if (enabled) drawRoundRect(color, Offset(0f, y - h / 2), Size(size.width * v, h), r)
        if (hovered && enabled) drawCircle(color, 6.dp.toPx(), Offset(size.width * v, y))
    }
}
