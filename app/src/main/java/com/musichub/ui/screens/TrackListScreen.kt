package com.musichub.ui.screens

import com.musichub.ui.liquid.LocalBottomInset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musichub.util.tracksCount
import com.musichub.App
import com.musichub.data.model.Track
import com.musichub.ui.AddToPlaylistDialog
import com.musichub.ui.Cover
import com.musichub.ui.CoverBackdrop
import com.musichub.ui.GeneratedCover
import com.musichub.ui.GlassIconButton
import com.musichub.ui.MetaChip
import com.musichub.ui.PrimaryPill
import com.musichub.ui.TrackRow
import com.musichub.vk.VkPlaylist
import kotlinx.coroutines.launch

/**
 * Список треков. kind: liked | yapl (owner:kind) | vkmy | vkpl (owner:id:key) | local (id) | downloads
 */
@Composable
fun TrackListScreen(kind: String, arg: String, title: String, onBack: () -> Unit) {
    val graph = App.graph
    val engine = graph.engine
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var addAll by remember { mutableStateOf(false) }

    LaunchedEffect(kind, arg, reload) {
        error = null
        if (kind == "downloads") {
            graph.db.downloads().observeAll().collect { list ->
                tracks = list.mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() }
            }
        } else {
            try {
                tracks = when (kind) {
                    "liked" -> graph.yandex.likedTracks()
                    "yapl" -> arg.split(':').let { graph.yandex.playlistTracks(it[0].toLong(), it[1].toLong()) }
                    "vkmy" -> graph.vk.myAudios()
                    "vkpl" -> arg.split(':').let {
                        graph.vk.playlistTracks(VkPlaylist(it[0].toLong(), it[1].toLong(), it.getOrNull(2)?.ifEmpty { null }, title, 0, null))
                    }
                    "local" -> graph.library.tracks(arg.toLong())
                    else -> emptyList()
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    val list = tracks
    val heroCover = list?.firstNotNullOfOrNull { it.coverUrl }

    Box(Modifier.fillMaxSize()) {
        CoverBackdrop(heroCover, strength = 0.8f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
            }
            when {
                error != null -> Column(Modifier.padding(16.dp)) {
                    ErrorText(error)
                    PrimaryPill("Повторить", null, onClick = { reload++ })
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                    item {
                        Hero(
                            kind = kind,
                            title = title,
                            cover = heroCover,
                            tracks = list,
                            onPlay = { engine.playTracks(list) },
                            onShuffle = { engine.playTracks(list, shuffle = true) },
                            onDownload = { engine.download(list) },
                            onAddAll = { addAll = true },
                        )
                    }
                    itemsIndexed(list, key = { _, t -> t.key }) { i, t ->
                        val extra = if (kind == "local") {
                            listOf("Убрать из плейлиста" to {
                                scope.launch {
                                    graph.library.remove(arg.toLong(), t.key)
                                    reload++
                                }
                                Unit
                            })
                        } else emptyList()
                        TrackRow(t, onClick = { engine.playTracks(list, i) }, extraActions = extra)
                    }
                }
            }
        }
    }
    if (addAll) AddToPlaylistDialog(tracks.orEmpty(), defaultName = title, onDismiss = { addAll = false })
}

@Composable
private fun Hero(
    kind: String,
    title: String,
    cover: String?,
    tracks: List<Track>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onAddAll: () -> Unit,
) {
    val totalMin = tracks.sumOf { it.durationMs } / 60_000
    val duration = if (totalMin >= 60) "${totalMin / 60} ч ${totalMin % 60} мин" else "$totalMin мин"
    val subtitle = when (kind) {
        "liked", "yapl" -> "Яндекс Музыка"
        "vkmy", "vkpl" -> "ВКонтакте"
        "local" -> "Мой плейлист"
        "downloads" -> "На устройстве"
        else -> ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val artModifier = Modifier.size(128.dp)
            when (kind) {
                "liked" -> GeneratedCover(Icons.Rounded.Favorite, Color(0xFF7A4DFF), artModifier)
                "vkmy" -> GeneratedCover(Icons.Rounded.LibraryMusic, Color(0xFF4D71FF), artModifier)
                "downloads" -> GeneratedCover(Icons.Rounded.CloudDownload, Color(0xFF3DDC97), artModifier)
                "local" -> GeneratedCover(Icons.AutoMirrored.Rounded.QueueMusic, Color(0xFF2EC4B6), artModifier)
                else -> Cover(cover, 128.dp, corner = 12.dp)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                MetaChip("${tracksCount(tracks.size)} · $duration")
            }
        }
        Row(
            Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryPill("Слушать", Icons.Rounded.PlayArrow, onPlay, enabled = tracks.isNotEmpty())
            GlassIconButton(Icons.Rounded.Shuffle, "Вперемешку", onShuffle)
            if (kind != "downloads") GlassIconButton(Icons.Rounded.Download, "Скачать всё", onDownload)
            if (kind != "local" && kind != "downloads") GlassIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "В мои плейлисты", onAddAll)
        }
    }
}
