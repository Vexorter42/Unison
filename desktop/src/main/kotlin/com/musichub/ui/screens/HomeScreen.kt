package com.musichub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.ui.GlassChip
import com.musichub.ui.LocalEngine
import com.musichub.ui.LocalGraph
import com.musichub.ui.PrimaryPill
import com.musichub.ui.SectionTitle
import com.musichub.ui.TrackRow
import com.musichub.ui.theme.LocalPalette
import kotlinx.coroutines.launch

private val moods = listOf("all" to "Любое", "active" to "Бодрое", "fun" to "Весёлое", "calm" to "Спокойное", "sad" to "Грустное")
private val diversities = listOf("default" to "Обычно", "favorite" to "Любимое", "discover" to "Незнакомое", "popular" to "Популярное")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(openSettings: () -> Unit) {
    val graph = LocalGraph.current
    val engine = LocalEngine.current
    val settings by graph.settings.flow.collectAsState()
    val state by engine.state.collectAsState()
    val palette = LocalPalette.current
    val waveOn = state.waveMode && state.radioTitle == "Моя волна"

    fun applyWaveSettings(mood: String, diversity: String) = graph.scope.launch {
        graph.settings.update { it.copy(waveMood = mood, waveDiversity = diversity) }
        val r = runCatching { graph.yandex.setWaveSettings(mood, diversity) }
        // Новые настройки волны действуют на следующие треки — перезапускаем, если волна уже играет
        if (r.isSuccess && waveOn) engine.startWave()
        r.exceptionOrNull()?.let { engine.notify("Настройки волны: ${it.message}") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text("Главная", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // Карточка «Моя волна»
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(palette.gradient)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))),
        ) {
            Column(Modifier.align(Alignment.BottomStart).padding(28.dp)) {
                Text("Моя волна", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(
                    if (settings.yandexLoggedIn) "Бесконечный поток под ваш вкус из Яндекс Музыки"
                    else "Войдите в Яндекс Музыку, чтобы включить волну",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(16.dp))
                if (settings.yandexLoggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(Color.White)
                                .clickable { if (waveOn) engine.togglePlay() else engine.startWave() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (waveOn && state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                "Моя волна",
                                tint = Color(0xFF0D0D10),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        if (waveOn && state.track != null) {
                            Column {
                                Text("Сейчас", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                Text("${state.track?.artists} — ${state.track?.title}", color = Color.White, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                } else {
                    PrimaryPill("Войти в Яндекс", null, onClick = openSettings)
                }
            }
        }

        if (settings.yandexLoggedIn) {
            Spacer(Modifier.height(20.dp))
            Text("Настроение", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                moods.forEach { (id, label) ->
                    GlassChip(label, selected = settings.waveMood == id) { applyWaveSettings(id, settings.waveDiversity) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Характер", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                diversities.forEach { (id, label) ->
                    GlassChip(label, selected = settings.waveDiversity == id) { applyWaveSettings(settings.waveMood, id) }
                }
            }
        }

        if (state.waveMode && state.queue.size > state.index + 1) {
            Spacer(Modifier.height(24.dp))
            SectionTitle("Дальше в «${state.radioTitle}»")
            state.queue.drop(state.index + 1).take(8).forEachIndexed { i, t ->
                TrackRow(t, index = null, onPlay = { engine.skipTo(state.index + 1 + i) })
            }
        }
    }
}
