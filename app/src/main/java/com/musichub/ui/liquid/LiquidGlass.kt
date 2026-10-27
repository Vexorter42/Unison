package com.musichub.ui.liquid

import com.musichub.ui.theme.LocalPalette
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import kotlin.math.abs

/** Включена ли тема Liquid Glass. */
val LocalLiquidGlass = compositionLocalOf { true }

/** Сколько места снизу занимают плавающие мини-плеер и бар — спискам нужен такой отступ. */
val LocalBottomInset = compositionLocalOf { 0.dp }

private val Ink = Color(0xFF0B0B12)

/** Медленно плавающие цветные пятна: стеклу нужно, что преломлять. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ambient")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(17_000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val palette = LocalPalette.current
    Canvas(modifier.fillMaxSize()) {
        drawRect(Ink)
        val w = size.width
        val h = size.height
        fun blob(color: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        blob(palette.a.copy(alpha = 0.55f), w * (0.1f + 0.55f * a), h * (0.1f + 0.18f * b), w * 0.95f)
        blob(palette.b.copy(alpha = 0.40f), w * (0.95f - 0.45f * b), h * (0.42f + 0.16f * a), w * 0.8f)
        blob(palette.c.copy(alpha = 0.30f), w * (0.2f + 0.35f * b), h * (0.72f - 0.12f * a), w * 0.75f)
        blob(palette.a.copy(alpha = 0.22f), w * (0.8f - 0.3f * a), h * (0.98f - 0.2f * b), w * 0.7f)
    }
}

/**
 * «Стеклянная» подложка для элементов внутри экрана: полупрозрачная заливка, светлый край сверху
 * и мягкий спад к низу. В классической теме — обычная непрозрачная поверхность.
 */
fun Modifier.glass(shape: Shape = RoundedCornerShape(22.dp), strong: Boolean = false): Modifier = composed {
    if (LocalLiquidGlass.current) {
        this
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(if (strong) 0.17f else 0.10f), Color.White.copy(if (strong) 0.08f else 0.035f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.32f), Color.White.copy(0.05f))), shape)
    } else {
        this.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }
}

/** Стеклянная панель вместо Material Card (внутренние отступы задаёт содержимое). */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass(), content = content)
}

/** Пружинное «сжатие» при нажатии — как будто жмёшь каплю. */
fun Modifier.pressSquish(interaction: MutableInteractionSource, pressedScale: Float = 0.93f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, spring(dampingRatio = 0.42f, stiffness = 520f), label = "squish")
    graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** Кликабельность без ripple, но с пружинным сжатием. */
fun Modifier.liquidClickable(enabled: Boolean = true, pressedScale: Float = 0.93f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    pressSquish(interaction, pressedScale).clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * Настоящее стекло для плавающих элементов (бар, мини-плеер): размывает и преломляет то,
 * что под ним. Без [backdrop] или в классической теме — непрозрачная капсула.
 */
fun Modifier.liquidCapsule(backdrop: Backdrop?, tint: Color = Ink.copy(alpha = 0.32f)): Modifier = composed {
    if (LocalLiquidGlass.current && backdrop != null) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(10f.dp.toPx())
                lens(22f.dp.toPx(), 30f.dp.toPx())
            },
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
    }
}

// ---------------------------------------------------------------------------------------------
// Переключатель

/**
 * Переключатель-«капля»: бегунок тянется в сторону движения, а при нажатии раздувается
 * и становится прозрачной линзой, через которую видна дорожка.
 */
@Composable
fun LiquidSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val liquid = LocalLiquidGlass.current
    val progress = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { progress.animateTo(if (checked) 1f else 0f, spring(dampingRatio = 0.58f, stiffness = 360f)) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
    val trackBackdrop = rememberLayerBackdrop()
    val trackBrush = LocalPalette.current.horizontal

    val trackW = 52.dp
    val trackH = 30.dp
    val thumb = 24.dp
    val pad = 3.dp
    Box(
        Modifier
            .size(trackW, trackH)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interaction, indication = null, enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Дорожка: серое стекло → фирменный градиент
        Box(
            Modifier
                .size(trackW, trackH)
                .layerBackdrop(trackBackdrop)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.14f))
                    drawRect(trackBrush, alpha = progress.value)
                },
        )
        val stretch = (abs(progress.velocity) * 0.05f).coerceAtMost(0.35f) + 0.3f * press
        val thumbModifier = Modifier
            .offset(x = pad + (trackW - thumb - pad * 2) * progress.value)
            .size(thumb)
            .graphicsLayer {
                scaleX = 1f + stretch
                scaleY = 1f + 0.12f * press - stretch * 0.2f
            }
        if (liquid) {
            Box(
                thumbModifier.drawBackdrop(
                    backdrop = trackBackdrop,
                    shape = { Capsule() },
                    effects = { lens(6f.dp.toPx() * (0.4f + press), 10f.dp.toPx() * (0.4f + press), chromaticAberration = true) },
                    highlight = { Highlight.Default },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - 0.8f * press)) },
                ),
            )
        } else {
            Box(thumbModifier.clip(CircleShape).background(Color.White))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Нижний бар

class NavItem(val label: String, val icon: ImageVector, val activeIcon: ImageVector)

/**
 * Плавающий бар-капсула. Выделение — стеклянный пузырь, который перетекает к выбранной вкладке:
 * в полёте вытягивается по горизонтали и сплющивается, а на месте «оседает» пружиной.
 */
@Composable
fun LiquidNavBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val liquid = LocalLiquidGlass.current
    val position = remember { Animatable(selected.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(selected) {
        if (selected >= 0) position.animateTo(selected.toFloat(), spring(dampingRatio = 0.6f, stiffness = 240f))
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(66.dp).liquidCapsule(backdrop)) {
        val inner = 5.dp
        val tabW: Dp = (maxWidth - inner * 2) / items.size
        if (selected >= 0) {
            val bubble = Modifier
                .padding(start = inner, top = inner, bottom = inner)
                .width(tabW)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = position.value * tabW.toPx()
                    val v = (abs(position.velocity) * 0.11f).coerceAtMost(0.45f)
                    scaleX = 1f + v
                    scaleY = 1f - v * 0.3f
                }
            if (liquid && backdrop != null) {
                Box(
                    bubble.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(4f.dp.toPx())
                            lens(14f.dp.toPx(), 22f.dp.toPx(), chromaticAberration = true)
                        },
                        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.16f)) },
                    ),
                )
            } else {
                Box(bubble.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)))
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = inner)) {
            items.forEachIndexed { i, item ->
                val active = i == selected
                val tint by animateColorAsState(
                    if (active) Color.White else Color.White.copy(alpha = 0.55f), label = "tint",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().liquidClickable(pressedScale = 0.86f) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (active) item.activeIcon else item.icon, item.label, tint = tint, modifier = Modifier.size(24.dp))
                    Text(item.label, fontSize = 10.sp, color = tint, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}
