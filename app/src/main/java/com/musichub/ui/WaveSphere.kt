package com.musichub.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Текучее свечение вокруг тёмного центра: два слоя шума медленно закручиваются, а басы мягко
 * усиливают свет. Без движения — неподвижная картинка; до Android 13 — мягкий градиентный ореол.
 */
private const val SPHERE_AGSL = """
uniform float2 size;
uniform float2 center;
uniform float time;
uniform float bass;
uniform float3 colA;
uniform float3 colB;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float fbm(float2 p) {
    float v = 0.60 * noise(p);
    p = p * 2.03 + float2(13.7, 9.2);
    v += 0.28 * noise(p);
    p = p * 2.01 + float2(7.1, 17.3);
    return v + 0.12 * noise(p);
}

half4 main(float2 fc) {
    float2 uv = (fc - center) / min(size.x, size.y);
    float r = length(uv);
    float spin = time * 0.075 + 0.38 * exp(-r * 2.8);
    float cs = cos(spin);
    float sn = sin(spin);
    float2 p = float2(uv.x * cs - uv.y * sn, uv.x * sn + uv.y * cs);

    float cloud = fbm(p * 2.0 + float2(time * 0.045, -time * 0.035));
    float detail = fbm(p * 3.8 + float2(cloud * 1.7, -cloud * 1.4) + float2(-time * 0.025, time * 0.04));
    float radius = 0.30 * (1.0 + 0.12 * bass);
    float edge = r + (cloud - 0.5) * 0.20 + (detail - 0.5) * 0.065;
    float outer = 1.0 - smoothstep(radius * 0.85, radius * 1.8, edge);
    float inner = smoothstep(radius * 0.30, radius * 0.90, edge);
    float silk = smoothstep(0.18, 0.82, detail) * 0.72 + cloud * cloud * 0.28;
    float light = outer * inner * (0.22 + silk * 0.95) * (0.82 + 0.48 * bass);
    float3 col = mix(colA, colB, smoothstep(0.18, 0.82, cloud));
    float3 c = col * light * 1.35;
    float al = clamp(max(c.r, max(c.g, c.b)), 0.0, 1.0);
    return half4(half3(min(c, float3(al))), half(al));
}
"""

/**
 * @param motion сфера двигается и слушает басы; иначе — неподвижна.
 * @param bass текущая сила басов 0..1 (читается каждый кадр).
 * @param center центр сферы в пикселях внутри области (null — середина).
 */
@Composable
fun WaveSphere(colorA: Color, colorB: Color, motion: Boolean, bass: () -> Float, center: () -> Offset?, modifier: Modifier = Modifier) {
    var time by remember { mutableFloatStateOf(7f) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        if (!motion) {
            level = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1e9f
                last = now
                level = bass()
            }
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(SPHERE_AGSL) }
        val brush = remember(shader) { ShaderBrush(shader) }
        Canvas(modifier) {
            val c = center() ?: Offset(size.width / 2, size.height / 2)
            shader.setup(this, c, time, level, colorA, colorB)
            drawRect(brush)
        }
    } else {
        Canvas(modifier) {
            val c = center() ?: Offset(size.width / 2, size.height / 2)
            val r = size.minDimension * 0.54f
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.28f to Color.Transparent,
                    0.50f to colorB.copy(alpha = 0.22f),
                    0.65f to colorA.copy(alpha = 0.55f + level * 0.15f),
                    0.84f to colorB.copy(alpha = 0.18f),
                    1f to Color.Transparent,
                    center = c,
                    radius = r,
                ),
                r,
                c,
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.setup(scope: DrawScope, c: Offset, time: Float, bass: Float, a: Color, b: Color) {
    setFloatUniform("size", scope.size.width, scope.size.height)
    setFloatUniform("center", c.x, c.y)
    setFloatUniform("time", time)
    setFloatUniform("bass", bass)
    setFloatUniform("colA", a.red, a.green, a.blue)
    setFloatUniform("colB", b.red, b.green, b.blue)
}
