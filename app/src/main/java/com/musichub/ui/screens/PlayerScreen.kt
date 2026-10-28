@file:OptIn(ExperimentalMaterial3Api::class)

package com.musichub.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.musichub.App
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.lyrics.Lyrics
import com.musichub.ui.Cover
import com.musichub.ui.CoverBackdrop
import com.musichub.ui.SourceBadge
import com.musichub.ui.swipeTracks
import com.musichub.util.formatTime
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val graph = App.graph
    val engine = graph.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val liked by graph.favorites.keys.collectAsStateWithLifecycle()
    val downloaded by engine.downloadedKeys.collectAsStateWithLifecycle()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    val track = state.track
    if (track == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val sleepActive = state.sleepAtMs != null || state.sleepEndOfTrack

    // Свайп вниз сворачивает плеер в мини-плеер: экран едет за пальцем, уменьшается и скругляется
    val scope = rememberCoroutineScope()
    val dragY = remember { Animatable(0f) }
    var screenHeight by remember { mutableFloatStateOf(1f) }
    var closing by remember { mutableStateOf(false) }
    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            dragY.animateTo(screenHeight, tween(230))
            onBack()
        }
    }
    BackHandler { dismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screenHeight = it.height.toFloat() }
            .graphicsLayer {
                val f = (dragY.value / screenHeight).coerceIn(0f, 1f)
                translationY = dragY.value
                scaleX = 1f - 0.1f * f
                scaleY = 1f - 0.1f * f
                shape = RoundedCornerShape((36 * f.coerceAtMost(0.3f) / 0.3f).dp)
                clip = f > 0f
            }
            .draggable(
                rememberDraggableState { delta ->
                    scope.launch { dragY.snapTo((dragY.value + delta).coerceAtLeast(0f)) }
                },
                Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (dragY.value > screenHeight * 0.2f || velocity > 1600f) dismiss()
                    else dragY.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 420f))
                },
            ),
    ) {
    // Как в Liner: цвет экрана берётся из обложки
    CoverBackdrop(track.coverUrl)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // «Ручка» — подсказка, что экран можно смахнуть вниз
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(width = 40.dp, height = 5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f)),
        )
        // Слева одна кнопка, справа две — поэтому заголовок кладём поверх ряда, строго по центру экрана
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { dismiss() }) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { sleepDialog = true }) {
                    Icon(Icons.Rounded.Bedtime, "Таймер сна", tint = if (sleepActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Rounded.Lyrics, "Текст", tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                state.radioTitle ?: "Сейчас играет",
                // Отступы по 96 dp — ширина двух кнопок справа, чтобы длинное «Радио: …» не наезжало на иконки
                modifier = Modifier.padding(horizontal = 96.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            if (showLyrics) {
                LyricsView(track, state.positionMs, onSeek = engine::seekTo)
            } else {
                val swipe = remember { Animatable(0f) }
                Cover(
                    track.coverUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .swipeTracks(swipe, canNext = state.hasNext, onNext = { engine.next() }, onPrevious = engine::previousTrack)
                        .graphicsLayer {
                            translationX = swipe.value
                            val f = (swipe.value / size.width).coerceIn(-1f, 1f)
                            rotationZ = f * 6f
                            alpha = 1f - abs(f) * 0.6f
                        },
                    corner = 16.dp,
                )
            }
        }

        Text(track.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(track.artists, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(track.source)
            if (state.viaFallback) Text("играет замена из другого сервиса", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            if (track.mixed) Text("микс YouTube", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            state.sleepAtMs?.let {
                val left = ((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                Text("сон через $left мин", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (state.sleepEndOfTrack) Text("сон после трека", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = (dragging ?: state.positionMs.toFloat()).coerceIn(0f, duration.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { engine.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(dragging?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = engine::toggleShuffle, enabled = !state.waveMode) {
                Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = engine::previous) { Icon(Icons.Rounded.SkipPrevious, "Назад", Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = engine::togglePlay,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза/плей", Modifier.size(40.dp))
            }
            IconButton(onClick = { engine.next() }, enabled = state.hasNext) { Icon(Icons.Rounded.SkipNext, "Дальше", Modifier.size(36.dp)) }
            IconButton(onClick = engine::cycleRepeat, enabled = !state.waveMode) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Повтор",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val isLiked = track.key in liked
            IconButton(onClick = engine::toggleLike) {
                Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится", tint = if (isLiked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = engine::dislike) { Icon(Icons.Rounded.ThumbDown, "Не нравится") }
            if (track.key in downloaded) {
                IconButton(onClick = { engine.removeDownload(track) }) { Icon(Icons.Rounded.DownloadDone, "Удалить загрузку", tint = MaterialTheme.colorScheme.primary) }
            } else {
                IconButton(onClick = { engine.download(listOf(track)) }) { Icon(Icons.Rounded.Download, "Скачать") }
            }
            IconButton(onClick = { engine.startRadio(track) }) { Icon(Icons.Rounded.Radio, "Радио по треку") }
            IconButton(onClick = { queueSheet = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь") }
        }
    }
    }

    if (sleepDialog) {
        AlertDialog(
            onDismissRequest = { sleepDialog = false },
            title = { Text("Таймер сна") },
            text = {
                Column {
                    listOf(15, 30, 45, 60, 90).forEach { m ->
                        TextButton(onClick = { engine.setSleepTimer(m); sleepDialog = false }) { Text("Через $m минут") }
                    }
                    TextButton(onClick = { engine.setSleepEndOfTrack(); sleepDialog = false }) { Text("В конце трека") }
                    if (sleepActive) TextButton(onClick = { engine.setSleepTimer(null); sleepDialog = false }) { Text("Выключить таймер") }
                }
            },
            confirmButton = { TextButton(onClick = { sleepDialog = false }) { Text("Закрыть") } },
        )
    }

    if (queueSheet) {
        ModalBottomSheet(onDismissRequest = { queueSheet = false }) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.index.coerceAtLeast(0))
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(state.queue) { i, t ->
                    ListItem(
                        headlineContent = {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == state.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = { Text(t.artists, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Cover(t.coverUrl, 40.dp) },
                        trailingContent = { SourceBadge(t.source) },
                        modifier = Modifier.clickable { engine.skipTo(i) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsView(track: Track, positionMs: Long, onSeek: (Long) -> Unit) {
    val result by produceState<Pair<Boolean, Lyrics?>>(false to null, track.key) {
        value = true to runCatching { App.graph.lyrics.get(track) }.getOrNull()
    }
    val (loaded, lyrics) = result
    when {
        !loaded -> CircularProgressIndicator()
        lyrics == null -> Text("Текст не найден", color = MaterialTheme.colorScheme.onSurfaceVariant)
        lyrics.synced != null -> {
            val lines = lyrics.synced
            val current = lines.indexOfLast { it.timeMs <= positionMs + 300 }
            val listState = rememberLazyListState()
            LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem((current - 3).coerceAtLeast(0)) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { i, line ->
                    Text(
                        line.text.ifBlank { "♪" },
                        // Крупно и жирно, неактивные строки приглушены — как полноэкранный текст в Liner
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (i == current) 1f else 0.32f),
                        modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 10.dp),
                    )
                }
                item { Text("Тексты: LRCLIB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 16.dp)) }
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(lyrics.plain.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            Text("Тексты: LRCLIB (без синхронизации)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}
