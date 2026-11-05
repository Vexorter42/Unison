package com.musichub.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musichub.App
import com.musichub.data.model.Track
import com.musichub.ui.CoverBackdrop
import com.musichub.ui.GlassIconButton
import com.musichub.ui.PrimaryPill
import com.musichub.ui.TrackRow
import com.musichub.ui.liquid.LocalBottomInset

/** «Похожие на этот трек»: радио Яндекса по треку + похожие с YouTube, одним списком. */
@Composable
fun SimilarScreen(onBack: () -> Unit) {
    val graph = App.graph
    val engine = graph.engine
    val seed = remember { graph.pendingSimilar.value }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }

    LaunchedEffect(seed?.key) {
        graph.pendingSimilar.value = null
        if (seed == null) {
            onBack()
            return@LaunchedEffect
        }
        tracks = runCatching { graph.sources.similar(seed) }.getOrNull() ?: emptyList()
    }
    if (seed == null) return

    Box(Modifier.fillMaxSize()) {
        CoverBackdrop(seed.coverUrl, strength = 0.8f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text("Похожие", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${seed.artists} — ${seed.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val list = tracks
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    "Похожие не нашлись — попробуйте позже",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            PrimaryPill("Слушать всё", Icons.Rounded.PlayArrow, onClick = { engine.playTracks(list) })
                        }
                    }
                    itemsIndexed(list, key = { _, t -> t.key }) { i, t ->
                        TrackRow(t, onClick = { engine.playTracks(list, i) })
                    }
                }
            }
        }
    }
}
