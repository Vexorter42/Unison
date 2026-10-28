package com.musichub.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.musichub.App
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.links.Links
import com.musichub.ui.liquid.liquidCapsule
import com.musichub.ui.liquid.liquidClickable
import com.musichub.ui.theme.LocalPalette
import com.musichub.util.formatTime
import com.musichub.util.tracksCount
import kotlinx.coroutines.launch

fun NavController.openTracks(kind: String, arg: String, title: String) =
    navigate("tracks/$kind/${Uri.encode(arg)}/${Uri.encode(title)}")

@Composable
fun Cover(url: String?, size: Dp = Dp.Unspecified, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    Box(
        modifier
            .then(if (size != Dp.Unspecified) Modifier.size(size) else Modifier)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
fun SourceBadge(source: Source, highlighted: Boolean = true) {
    val color = when (source) {
        Source.YANDEX -> MaterialTheme.colorScheme.tertiary
        Source.VK -> MaterialTheme.colorScheme.primary
        Source.YOUTUBE -> MaterialTheme.colorScheme.error
        Source.LOCAL -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        color = if (highlighted) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            source.short,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun SourceBadges(track: Track) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        SourceBadge(track.source)
        track.alternates.forEach { SourceBadge(it.source, highlighted = false) }
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val engine = App.graph.engine
    val currentKey by engine.currentKey.collectAsStateWithLifecycle()
    val downloaded by engine.downloadedKeys.collectAsStateWithLifecycle()
    val downloading by engine.downloading.collectAsStateWithLifecycle()
    val favorites by App.graph.favorites.keys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    val isCurrent = currentKey == track.key

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Cover(track.coverUrl, 48.dp) },
        headlineContent = {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadges(track)
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        append(track.artists)
                        if (track.durationMs > 0) append(" · ").append(formatTime(track.durationMs))
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (track.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                downloading[track.key]?.let { p ->
                    CircularProgressIndicator(progress = { p }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } ?: if (track.key in downloaded) {
                    Icon(Icons.Rounded.DownloadDone, "Скачано", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                } else Unit
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (track.key in favorites) "Убрать из «Любимого»" else "В «Любимое»") },
                            onClick = { menu = false; engine.toggleLike(track) },
                        )
                        DropdownMenuItem(text = { Text("Радио по треку") }, onClick = { menu = false; engine.startRadio(track) })
                        DropdownMenuItem(text = { Text("Играть следующим") }, onClick = { menu = false; engine.playNext(track) })
                        DropdownMenuItem(text = { Text("В очередь") }, onClick = { menu = false; engine.enqueue(track) })
                        DropdownMenuItem(text = { Text("В мой плейлист…") }, onClick = { menu = false; addDialog = true })
                        if (track.source == Source.LOCAL) {
                            // Файл уже на телефоне — скачивать нечего
                        } else if (track.key in downloaded) {
                            DropdownMenuItem(text = { Text("Удалить загрузку") }, onClick = { menu = false; engine.removeDownload(track) })
                        } else {
                            DropdownMenuItem(text = { Text("Скачать") }, onClick = { menu = false; engine.download(listOf(track)) })
                        }
                        Links.trackUrl(track)?.let { url ->
                            DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menu = false; shareTrack(context, track, url) })
                        }
                        extraActions.forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                        }
                    }
                }
            }
        },
    )
    if (addDialog) AddToPlaylistDialog(listOf(track), defaultName = "", onDismiss = { addDialog = false })
}

/** Выбор своего плейлиста (или создание нового) для добавления треков. */
@Composable
fun AddToPlaylistDialog(tracks: List<Track>, defaultName: String, onDismiss: () -> Unit) {
    val graph = App.graph
    val playlists by graph.library.playlists.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf(defaultName) }
    val context = LocalContext.current

    fun addTo(id: Long) = scope.launch {
        val n = graph.library.add(id, tracks)
        onDismiss()
        Toast.makeText(context, "Добавлено треков: $n", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист") },
        text = {
            Column {
                LazyColumn(Modifier.height(220.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(tracksCount(p.count)) },
                            modifier = Modifier.clickable { addTo(p.id) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Новый плейлист") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = newName.isNotBlank(),
                        onClick = { scope.launch { addTo(graph.library.create(newName.trim())) } },
                    ) { Icon(Icons.Rounded.Add, "Создать") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/**
 * Мини-плеер — плавающая стеклянная капсула. Обложка-«пластинка» крутится, пока играет музыка;
 * тап или свайп вверх открывает полный плеер.
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, backdrop: Backdrop?) {
    val engine = App.graph.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val track = state.track ?: return
    val spin = remember { Animatable(0f) }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            spin.animateTo(spin.value + 360f, tween(9_000, easing = LinearEasing))
        }
    }
    var dragged by remember { mutableFloatStateOf(0f) }
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .liquidCapsule(backdrop)
            .liquidClickable(pressedScale = 0.97f, onClick = onOpen)
            .draggable(
                rememberDraggableState { d -> dragged += d },
                Orientation.Vertical,
                onDragStopped = {
                    if (dragged < -40f) onOpen()
                    dragged = 0f
                },
            ),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.coverUrl, 44.dp, Modifier.graphicsLayer { rotationZ = spin.value }, corner = 22.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    track.artists,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            if (state.isBuffering) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                IconButton(onClick = engine::togglePlay) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Пауза/плей", tint = Color.White)
                }
            }
            IconButton(onClick = { engine.next() }, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, "Дальше", tint = Color.White.copy(alpha = if (state.hasNext) 1f else 0.4f))
            }
        }
        // Тонкая полоска прогресса по нижнему краю капсулы
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 30.dp, vertical = 5.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
        ) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(LocalPalette.current.horizontal))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Плашка над мини-плеером: общий прогресс скачивания с кнопкой отмены. */
@Composable
fun DownloadPill(backdrop: Backdrop?) {
    val engine = App.graph.engine
    val batch by engine.downloadBatch.collectAsStateWithLifecycle()
    val b = batch ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(50.dp)
            .liquidCapsule(backdrop),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                progress = { b.progress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    if (b.active) "Скачиваю ${minOf(b.finished + 1, b.total)} из ${b.total} · ${(b.progress * 100).toInt()}%"
                    else "Скачано ${b.done} из ${b.total}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
                b.currentTitle?.let {
                    Text(it, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (b.active) {
                IconButton(onClick = engine::cancelDownloads) { Icon(Icons.Rounded.Close, "Отменить загрузку", tint = Color.White) }
            }
        }
    }
}

/** «Поделиться» треком: исполнитель, название и ссылка на сервис. */
fun shareTrack(context: android.content.Context, track: Track, url: String) {
    val text = "${track.artists} — ${track.title}\n$url"
    context.startActivity(
        android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text),
            "Поделиться",
        ),
    )
}
