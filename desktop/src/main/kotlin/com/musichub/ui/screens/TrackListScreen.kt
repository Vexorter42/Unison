package com.musichub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.data.model.Track
import com.musichub.ui.Cover
import com.musichub.ui.GeneratedCover
import com.musichub.ui.GlassButton
import com.musichub.ui.GlassIconButton
import com.musichub.ui.Hint
import com.musichub.ui.LocalEngine
import com.musichub.ui.MetaChip
import com.musichub.ui.PrimaryPill
import com.musichub.ui.TrackRow
import com.musichub.util.tracksCount

@Composable
fun TrackListScreen(source: TrackSource, onBack: () -> Unit) {
    val result by produceState<Result<List<Track>>?>(null, source.id) {
        value = runCatching { source.load() }
    }
    val tracks = result?.getOrNull().orEmpty()
    TrackListLayout(
        title = source.title,
        subtitle = source.subtitle,
        header = { Cover(source.coverUrl ?: tracks.firstOrNull()?.coverUrl, size = 150.dp, corner = 16.dp) },
        tracks = tracks,
        loading = result == null,
        error = result?.exceptionOrNull()?.message,
        onBack = onBack,
    )
}

/** Общая раскладка списка треков: шапка с обложкой и кнопками «Слушать» / «Перемешать», ниже — треки. */
@Composable
fun TrackListLayout(
    title: String,
    subtitle: String,
    header: @Composable () -> Unit,
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    onBack: (() -> Unit)?,
    extraActions: @Composable () -> Unit = {},
) {
    val engine = LocalEngine.current
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        if (onBack != null) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClick = onBack, filled = true)
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            header()
            Column(Modifier.padding(start = 24.dp)) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (!loading) MetaChip(tracksCount(tracks.size))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryPill("Слушать", Icons.Rounded.PlayArrow, onClick = { engine.playTracks(tracks) }, enabled = tracks.isNotEmpty())
                    GlassButton("Перемешать", Icons.Rounded.Shuffle, onClick = { engine.playTracks(tracks, shuffle = true) }, enabled = tracks.isNotEmpty())
                    extraActions()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            error != null -> Text("Не загрузилось: $error", color = MaterialTheme.colorScheme.error)
            tracks.isEmpty() -> Hint("Здесь пока пусто")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(tracks, key = { i, t -> "$i:${t.key}" }) { i, t ->
                    TrackRow(t, index = i, onPlay = { engine.playTracks(tracks, i) })
                }
            }
        }
    }
}

@Composable
fun FavoritesHeaderCover() {
    GeneratedCover(Icons.Rounded.Favorite, Color(0xFFE0457B), Modifier.width(150.dp).height(150.dp))
}
