package com.musichub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.data.model.Track
import com.musichub.ui.Cover
import com.musichub.ui.GeneratedCover
import com.musichub.ui.LocalGraph
import com.musichub.ui.SectionTitle
import com.musichub.util.tracksCount
import com.musichub.yandex.YaPlaylist
import com.musichub.youtube.YtPlaylist

/** Экран со списком треков, который грузится по требованию (плейлист, «Мне нравится»…). */
class TrackSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
    val load: suspend () -> List<Track>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(open: (TrackSource) -> Unit, openFavorites: () -> Unit, openSettings: () -> Unit) {
    val graph = LocalGraph.current
    val settings by graph.settings.flow.collectAsState()
    val favorites by graph.favorites.items.collectAsState()
    val yaPlaylists by produceState<Result<List<YaPlaylist>>?>(null, settings.yandexUid) {
        value = if (settings.yandexLoggedIn) runCatching { graph.yandex.playlists() } else null
    }
    val ytPlaylists by produceState<Result<List<YtPlaylist>>?>(null, settings.ytCookie) {
        value = if (settings.ytLoggedIn) runCatching { graph.ytMusic.playlists() } else null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text("Медиатека", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Tile("Любимое Unison", tracksCount(favorites.size), onClick = openFavorites) {
                GeneratedCover(Icons.Rounded.Favorite, Color(0xFFE0457B), Modifier.fillMaxSize())
            }
            if (settings.yandexLoggedIn) {
                Tile("Мне нравится", "Яндекс Музыка", onClick = {
                    open(TrackSource("ya:liked", "Мне нравится", "Яндекс Музыка", null) { graph.yandex.likedTracks() })
                }) { GeneratedCover(Icons.Rounded.AutoAwesome, Color(0xFFFFB627), Modifier.fillMaxSize()) }
            }
            if (settings.ytLoggedIn) {
                Tile("Понравившиеся", "YouTube Music", onClick = {
                    open(TrackSource("yt:liked", "Понравившиеся", "YouTube Music", null) { graph.ytMusic.liked() })
                }) { GeneratedCover(Icons.Rounded.ThumbUp, Color(0xFFFF4D4D), Modifier.fillMaxSize()) }
            }
        }

        if (!settings.yandexLoggedIn) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Войдите в Яндекс Музыку, чтобы здесь появились «Мне нравится» и ваши плейлисты.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = openSettings),
            )
        }

        yaPlaylists?.let { r ->
            Spacer(Modifier.height(24.dp))
            SectionTitle("Плейлисты Яндекса")
            r.onFailure { Text("Не загрузились: ${it.message}", color = MaterialTheme.colorScheme.error) }
            r.onSuccess { list ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    list.forEach { p ->
                        Tile(p.title, tracksCount(p.trackCount), onClick = {
                            open(TrackSource("ya:${p.ownerUid}:${p.kind}", p.title, "Плейлист · Яндекс", p.coverUrl) {
                                graph.yandex.playlistTracks(p.ownerUid.toString(), p.kind)
                            })
                        }) { Cover(p.coverUrl, modifier = Modifier.fillMaxSize(), corner = 14.dp) }
                    }
                }
            }
        }

        ytPlaylists?.let { r ->
            Spacer(Modifier.height(24.dp))
            SectionTitle("Плейлисты YouTube Music")
            r.onFailure { Text("Не загрузились: ${it.message}", color = MaterialTheme.colorScheme.error) }
            r.onSuccess { list ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    list.forEach { p ->
                        Tile(p.title, p.subtitle, onClick = {
                            open(TrackSource("yt:${p.browseId}", p.title, "Плейлист · YouTube Music", p.coverUrl) {
                                graph.ytMusic.playlistTracks(p.browseId)
                            })
                        }) { Cover(p.coverUrl, modifier = Modifier.fillMaxSize(), corner = 14.dp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, subtitle: String, onClick: () -> Unit, cover: @Composable () -> Unit) {
    Column(Modifier.width(170.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().aspectRatio(1f)) { cover() }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
