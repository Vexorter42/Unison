package com.musichub.ui.screens

import com.musichub.ui.theme.LocalPalette
import com.musichub.ui.liquid.liquidClickable
import com.musichub.ui.liquid.glass
import com.musichub.ui.icons.UnisonIcons
import com.musichub.ui.liquid.LocalBottomInset
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import com.musichub.ui.liquid.GlassPanel
import androidx.compose.material3.FilledIconButton
import com.musichub.ui.GlassChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.musichub.App
import com.musichub.data.model.Track
import com.musichub.ui.SectionTitle
import com.musichub.ui.TrackRow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val moods = listOf("all" to "Любое", "active" to "Бодрое", "fun" to "Весёлое", "calm" to "Спокойное", "sad" to "Грустное")
private val diversities = listOf("default" to "Обычно", "favorite" to "Любимое", "discover" to "Незнакомое", "popular" to "Популярное")

@Composable
fun HomeScreen(nav: NavController) {
    val graph = App.graph
    val engine = graph.engine
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val recentFlow = remember {
        graph.db.stats().recent(20).map { list -> list.mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() } }
    }
    val recent by recentFlow.collectAsState(emptyList())
    var vkLoading by remember { mutableStateOf(false) }

    fun applyWaveSettings(mood: String, diversity: String) = scope.launch {
        graph.settings.update { it.copy(waveMood = mood, waveDiversity = diversity) }
        runCatching { graph.yandex.setWaveSettings(mood, diversity) }
        if (engine.state.value.waveMode) engine.startWave()
    }

    LazyColumn(Modifier.statusBarsPadding(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Unison",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Быстрое включение/выключение Liquid Glass
                Box(
                    Modifier
                        .size(44.dp)
                        .glass(CircleShape, strong = settings.liquidGlass)
                        .liquidClickable(pressedScale = 0.85f) {
                            scope.launch { graph.settings.update { it.copy(liquidGlass = !it.liquidGlass) } }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (settings.liquidGlass) UnisonIcons.DropActive else UnisonIcons.Drop,
                        "Liquid Glass",
                        tint = if (settings.liquidGlass) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        item {
            val playingWave = state.waveMode && state.isPlaying
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(LocalPalette.current.gradient),
            ) {
                Column(Modifier.padding(20.dp).align(Alignment.TopStart)) {
                    Text("Моя волна", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append("Яндекс Музыка")
                            if (settings.mixYoutubeInWave) append(" + YouTube каждый ${settings.mixEvery}-й")
                        },
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                FilledIconButton(
                    onClick = { if (state.waveMode) engine.togglePlay() else engine.startWave() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = LocalPalette.current.a),
                ) {
                    Icon(if (playingWave) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Моя волна", Modifier.size(36.dp))
                }
            }
        }
        if (!settings.yandexLoggedIn) {
            item {
                GlassPanel(Modifier.padding(16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Чтобы работала Моя волна, лайки и плейлисты — войдите в Яндекс")
                        Button(onClick = { nav.navigate("login/ya") }, modifier = Modifier.padding(top = 8.dp)) { Text("Войти в Яндекс") }
                    }
                }
            }
        }
        item { SectionTitle("Настроение волны") }
        item {
            ChipRow(moods, settings.waveMood) { applyWaveSettings(it, settings.waveDiversity) }
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                ChipRow(diversities, settings.waveDiversity) { applyWaveSettings(settings.waveMood, it) }
            }
        }
        if (settings.vkLoggedIn) {
            item {
                OutlinedButton(
                    enabled = !vkLoading,
                    onClick = {
                        vkLoading = true
                        scope.launch {
                            runCatching { graph.vk.recommendations() }
                                .onSuccess { engine.playTracks(it) }
                                .onFailure { engine.notify(it.message ?: "Ошибка ВК") }
                            vkLoading = false
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (vkLoading) "Загружаю…" else "Рекомендации ВКонтакте") }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Недавно слушали") }
            itemsIndexed(recent, key = { _, t -> t.key }) { i, t ->
                TrackRow(t, onClick = { engine.playTracks(recent, i) })
            }
        }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            GlassChip(label, selected = value == selected, onClick = { onSelect(value) })
        }
    }
}
