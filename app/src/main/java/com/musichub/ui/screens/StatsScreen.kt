package com.musichub.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.App
import com.musichub.data.db.HourRow
import com.musichub.data.db.SourceRow
import com.musichub.data.db.TopArtistRow
import com.musichub.data.db.TopTrackRow
import com.musichub.data.db.Totals
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.ui.Cover
import com.musichub.ui.GlassChip
import com.musichub.ui.liquid.GlassPanel
import com.musichub.ui.liquid.LocalBottomInset
import com.musichub.ui.liquid.liquidClickable
import com.musichub.util.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Calendar

private enum class Period(val label: String) {
    WEEK("Неделя"), MONTH("Месяц"), YEAR("Год"), ALL("Всё время");

    fun since(): Long {
        val now = System.currentTimeMillis()
        return when (this) {
            WEEK -> now - 7L * 86_400_000
            MONTH -> now - 30L * 86_400_000
            YEAR -> Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            ALL -> 0
        }
    }
}

private data class StatsData(
    val totals: Totals,
    val tracks: List<TopTrackRow>,
    val artists: List<TopArtistRow>,
    val sources: List<SourceRow>,
    val hours: List<HourRow>,
)

@Composable
fun StatsScreen() {
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    val dao = App.graph.db.stats()
    val data by produceState<StatsData?>(null, period) {
        value = null
        val s = period.since()
        value = withContext(Dispatchers.IO) {
            StatsData(dao.totals(s), dao.topTracks(s, 10), dao.topArtists(s, 5), dao.bySource(s), dao.byHour(s))
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = LocalBottomInset.current)) {
        Text(
            if (period == Period.YEAR) "Итоги ${Calendar.getInstance().get(Calendar.YEAR)}" else "Статистика",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        RecentlyPlayed()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p -> GlassChip(p.label, selected = p == period, onClick = { period = p }) }
        }
        val d = data
        when {
            d == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            d.totals.plays == 0 -> Text("Пока пусто — послушайте что-нибудь 🙂", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> StatsContent(d)
        }
    }
}

@Composable
private fun StatsContent(d: StatsData) {
    val minutes = d.totals.ms / 60_000
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (minutes > 0) "$minutes мин" else "${d.totals.ms / 1000} сек",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            if (minutes >= 60) Text("≈ ${"%.1f".format(minutes / 60.0)} ч музыки")
            val skipPct = if (d.totals.plays > 0) d.totals.skips * 100 / d.totals.plays else 0
            Text(
                "${plural(d.totals.plays, "прослушивание", "прослушивания", "прослушиваний")} · ${plural(d.totals.uniqueTracks, "разный трек", "разных трека", "разных треков")} · пропущено $skipPct%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Header("Откуда музыка")
    val totalMs = d.sources.sumOf { it.ms }.coerceAtLeast(1)
    d.sources.forEach { row ->
        val label = runCatching { Source.valueOf(row.source).label }.getOrDefault(row.source)
        val share = row.ms.toFloat() / totalMs
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(label, modifier = Modifier.width(120.dp))
            LinearProgressIndicator(progress = { share }, modifier = Modifier.weight(1f).height(8.dp))
            Text(" ${(share * 100).toInt()}%", modifier = Modifier.width(48.dp))
        }
    }

    Header("Когда слушаете")
    val byHour = LongArray(24).also { arr -> d.hours.forEach { if (it.hour in 0..23) arr[it.hour] = it.ms } }
    val peak = byHour.indices.maxBy { byHour[it] }
    val barColor = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val max = byHour.max().coerceAtLeast(1)
        val w = size.width / 24f
        byHour.forEachIndexed { h, ms ->
            val bh = (ms.toFloat() / max) * size.height
            drawRoundRect(
                color = if (h == peak) barColor else barColor.copy(alpha = 0.45f),
                topLeft = Offset(h * w + w * 0.15f, size.height - bh.coerceAtLeast(2f)),
                size = Size(w * 0.7f, bh.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
        drawLine(dim, Offset(0f, size.height), Offset(size.width, size.height))
    }
    // 24 ячейки той же ширины, что и столбики, — подпись стоит ровно под своим часом
    Row(Modifier.fillMaxWidth()) {
        repeat(24) { h ->
            Text(
                if (h % 6 == 0) "$h" else "",
                style = MaterialTheme.typography.labelSmall,
                softWrap = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text("Чаще всего — около $peak:00", color = MaterialTheme.colorScheme.onSurfaceVariant)

    Header("Топ треков")
    d.tracks.forEachIndexed { i, t ->
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${t.plays}×", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Header("Топ исполнителей")
    d.artists.forEachIndexed { i, a ->
        Row(Modifier.padding(vertical = 4.dp)) {
            Text("${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Text(a.artists, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${a.ms / 60_000} мин", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

/** «Недавно слушали» — лента обложек; тап включает трек, дальше играют остальные из ленты. */
@Composable
private fun RecentlyPlayed() {
    val graph = App.graph
    val recentFlow = remember {
        graph.db.stats().recent(20).map { list -> list.mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() } }
    }
    val recent by recentFlow.collectAsState(emptyList())
    if (recent.isEmpty()) return
    Header("Недавно слушали")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(recent, key = { _, t -> t.key }) { i, t ->
            Column(Modifier.width(112.dp).liquidClickable(pressedScale = 0.95f) { graph.engine.playTracks(recent, i) }) {
                Cover(t.coverUrl, 112.dp, corner = 14.dp)
                Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(t.artists, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
