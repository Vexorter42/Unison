package com.musichub.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.musichub.App
import com.musichub.ui.GlassChip
import com.musichub.ui.PrimaryPill
import com.musichub.ui.WaveSphere
import com.musichub.ui.icons.UnisonIcons
import com.musichub.ui.liquid.LocalBottomInset
import com.musichub.ui.liquid.glass
import com.musichub.ui.liquid.liquidClickable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** Настроение Моей волны: значение для API Яндекса, подпись, иконка и свои цвета. */
private class Mood(val value: String, val label: String, val icon: ImageVector, val from: Color, val to: Color)

private val moods = listOf(
    Mood("all", "Любое", Icons.Rounded.AllInclusive, Color(0xFF7B4DFF), Color(0xFFE040FB)),
    Mood("active", "Бодрое", Icons.Rounded.Bolt, Color(0xFFFF6D00), Color(0xFFFFC400)),
    Mood("fun", "Весёлое", Icons.Rounded.WbSunny, Color(0xFFFF4081), Color(0xFFFFD740)),
    Mood("calm", "Спокойное", Icons.Rounded.Spa, Color(0xFF00BFA5), Color(0xFF448AFF)),
    Mood("sad", "Грустное", Icons.Rounded.WaterDrop, Color(0xFF3D5AFE), Color(0xFF7C4DFF)),
)

private class Diversity(val value: String, val label: String, val icon: ImageVector)

private val diversities = listOf(
    Diversity("default", "Обычно", Icons.Rounded.Tune),
    Diversity("favorite", "Любимое", Icons.Rounded.Favorite),
    Diversity("discover", "Незнакомое", Icons.Rounded.Explore),
    Diversity("popular", "Популярное", Icons.Rounded.Whatshot),
)

@Composable
fun HomeScreen(nav: NavController) {
    val graph = App.graph
    val engine = graph.engine
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun applyWaveSettings(mood: String, diversity: String) = scope.launch {
        graph.settings.update { it.copy(waveMood = mood, waveDiversity = diversity) }
        runCatching { graph.yandex.setWaveSettings(mood, diversity) }
        if (engine.state.value.radioTitle == "Моя волна" || engine.state.value.waveMode) engine.startWave()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = LocalBottomInset.current)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Unison", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RoundGlassButton(Icons.Rounded.Search, "Поиск") { nav.navigate("search") }
            // Быстрое включение/выключение Liquid Glass
            RoundGlassButton(
                if (settings.liquidGlass) UnisonIcons.DropActive else UnisonIcons.Drop,
                "Liquid Glass",
                active = settings.liquidGlass,
            ) { scope.launch { graph.settings.update { it.copy(liquidGlass = !it.liquidGlass) } } }
        }

        val moodIndex = moods.indexOfFirst { it.value == settings.waveMood }.coerceAtLeast(0)
        val mood = moods[moodIndex]
        val diversity = diversities.firstOrNull { it.value == settings.waveDiversity } ?: diversities[0]
        val waveOn = state.waveMode && state.isPlaying
        // Басы меряем, только пока главная на экране, сфера живая и играет именно волна
        DisposableEffect(settings.sphereMotion, waveOn) {
            engine.bassMeter.enabled = settings.sphereMotion && waveOn
            onDispose { engine.bassMeter.enabled = false }
        }
        WaveHero(
            mood = mood,
            subtitle = "${mood.label} · ${diversity.label}",
            playing = waveOn,
            loggedIn = settings.yandexLoggedIn,
            sphereMotion = settings.sphereMotion,
            bass = { if (waveOn) engine.bassMeter.level() else 0f },
            onPlay = { if (state.waveMode) engine.togglePlay() else engine.startWave() },
            onLogin = { nav.navigate("login/ya") },
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        ) {
            MoodCarousel(moodIndex) { applyWaveSettings(moods[it].value, settings.waveDiversity) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                diversities.forEach { d ->
                    GlassChip(d.label, selected = d.value == settings.waveDiversity, icon = d.icon, onClick = {
                        applyWaveSettings(settings.waveMood, d.value)
                    })
                }
            }
        }
    }
}

@Composable
private fun RoundGlassButton(icon: ImageVector, description: String, active: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).glass(CircleShape, strong = active).liquidClickable(pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** Большая карточка Моей волны: живой градиент в цветах настроения, кнопка по центру, внизу — выбор настроения. */
@Composable
private fun WaveHero(
    mood: Mood,
    subtitle: String,
    playing: Boolean,
    loggedIn: Boolean,
    sphereMotion: Boolean,
    bass: () -> Float,
    onPlay: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit,
) {
    val from by animateColorAsState(mood.from, tween(700), label = "from")
    val to by animateColorAsState(mood.to, tween(700), label = "to")
    val t = rememberInfiniteTransition(label = "hero")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart), label = "pulse")

    var box by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var titleCenter by remember { mutableStateOf<Offset?>(null) }

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Color(0xFF0E0E14)).onGloballyPositioned { box = it }) {
        // Сфера из светящихся лент, центр — на надписи «Моя волна»
        WaveSphere(from, to, motion = sphereMotion, bass = bass, center = { titleCenter }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Моя волна",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.onGloballyPositioned { c ->
                        box?.takeIf { it.isAttached }?.let { b ->
                            titleCenter = b.localPositionOf(c, Offset(c.size.width / 2f, c.size.height / 2f))
                        }
                    },
                )
                Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(28.dp))
                if (loggedIn) {
                    Box(contentAlignment = Alignment.Center) {
                        // Пульсирующее кольцо, пока волна играет
                        if (playing) {
                            Canvas(Modifier.size(150.dp)) {
                                drawCircle(
                                    Color.White.copy(alpha = (1f - pulse) * 0.5f),
                                    radius = size.minDimension / 2 * (0.62f + 0.38f * pulse),
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                            }
                        }
                        Box(
                            Modifier.size(96.dp).clip(CircleShape).background(Color.White).liquidClickable(pressedScale = 0.9f, onClick = onPlay),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Моя волна", tint = from, modifier = Modifier.size(52.dp))
                        }
                    }
                } else {
                    Text(
                        "Войдите в Яндекс, чтобы слушать Мою волну",
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    PrimaryPill("Войти в Яндекс", null, onLogin)
                }
            }
            controls()
        }
    }
}

/** Карусель настроений: свайп или тап по карточке, соседние видны по краям. */
@Composable
private fun MoodCarousel(selected: Int, onSelect: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = selected) { moods.size }
    val scope = rememberCoroutineScope()
    // Выбор применяется, когда карусель остановилась на карточке
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { if (it != selected) onSelect(it) }
    }
    LaunchedEffect(selected) { if (pager.settledPage != selected && !pager.isScrollInProgress) pager.animateScrollToPage(selected) }

    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 112.dp),
        pageSpacing = 4.dp,
        modifier = Modifier.fillMaxWidth().height(132.dp),
    ) { page ->
        val m = moods[page]
        val distance = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.18f * distance
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.45f * distance
                }
                .glass(RoundedCornerShape(24.dp), strong = distance < 0.5f)
                .liquidClickable(pressedScale = 0.94f) { scope.launch { pager.animateScrollToPage(page) } },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(m.from, m.to))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(m.icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Text(m.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
