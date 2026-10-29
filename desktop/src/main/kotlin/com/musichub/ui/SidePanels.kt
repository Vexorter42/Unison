package com.musichub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.lyrics.Lyrics

/** Подложка боковой панели. */
@Composable
fun GlassPanel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF16161B).copy(alpha = 0.55f))
            .background(glassFill())
            .padding(16.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

/** Синхронный текст из LRCLIB: текущая строка подсвечена и держится по центру, по клику — перемотка. */
@Composable
fun LyricsPanel(modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val engine = LocalEngine.current
    val s by engine.state.collectAsState()
    val track = s.track
    val lyrics by produceState<Pair<String?, Lyrics?>>(null to null, track?.key) {
        value = track?.key to null
        value = track?.key to track?.let { runCatching { graph.lyrics.get(it) }.getOrNull() ?: Lyrics(null, null) }
    }
    GlassPanel("Текст песни", modifier) {
        val (key, l) = lyrics
        when {
            track == null -> Hint("Включите трек — здесь появится его текст")
            key != track.key || l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }
            !l.synced.isNullOrEmpty() -> SyncedLyrics(l, s.positionMs) { engine.seekTo(it) }
            !l.plain.isNullOrBlank() -> Text(
                l.plain,
                fontSize = 16.sp,
                lineHeight = 26.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
            else -> Hint("Текст для этого трека не нашёлся в LRCLIB")
        }
    }
}

@Composable
private fun SyncedLyrics(l: Lyrics, positionMs: Long, onSeek: (Long) -> Unit) {
    val lines = l.synced.orEmpty()
    // Небольшое опережение: строку удобнее видеть чуть раньше, чем её споют
    val current = lines.indexOfLast { it.timeMs <= positionMs + 250 }.coerceAtLeast(0)
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        list.animateScrollToItem((current - 3).coerceAtLeast(0))
    }
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == current -> MaterialTheme.colorScheme.onSurface
                    i < current -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
            )
            Text(
                line.text.ifBlank { "♪" },
                color = color,
                fontSize = if (i == current) 22.sp else 18.sp,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.SemiBold,
                lineHeight = 28.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSeek(line.timeMs) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun QueuePanel(modifier: Modifier = Modifier) {
    val engine = LocalEngine.current
    val s by engine.state.collectAsState()
    GlassPanel(s.radioTitle ?: "Очередь", modifier) {
        if (s.queue.isEmpty()) return@GlassPanel Hint("Очередь пуста")
        val list = rememberLazyListState()
        LaunchedEffect(Unit) { list.scrollToItem((s.index - 1).coerceAtLeast(0)) }
        LazyColumn(state = list) {
            itemsIndexed(s.queue, key = { i, t -> "$i:${t.key}" }) { i, t ->
                TrackRow(t, index = null, onPlay = { engine.skipTo(i) }, compact = true)
            }
        }
    }
}

@Composable
fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}
