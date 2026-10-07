package ai.visionmirror.design.halo

import ai.visionmirror.design.tokens.LocalReduceMotion
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Vm
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.min
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.launch

/** Loop phases for the always-on animations. Absent entirely under reduce-motion. */
@Stable
private class LoopPhases(
    val breath: State<Float>,
    val angle: State<Float>,
    val ripple: State<Float>,
)

private val StaticPhases = LoopPhases(mutableStateOf(0f), mutableStateOf(0f), mutableStateOf(0f))

@Composable
private fun rememberLoopPhases(): LoopPhases {
    val t = rememberInfiniteTransition(label = "halo")
    val breath = t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(Motion.Duration.BREATH / 2, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "breath",
    )
    val angle = t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(Motion.Duration.SHIMMER, easing = LinearEasing)),
        label = "shimmer",
    )
    val ripple = t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(Motion.Duration.RIPPLE, easing = LinearEasing)),
        label = "ripple",
    )
    return remember(breath, angle, ripple) { LoopPhases(breath, angle, ripple) }
}

/**
 * The signature element: a living ring that is also the status indicator.
 *
 * Performance: every animated value is read inside the draw lambda only, so animation never
 * recomposes. On API 33+ the glow is an AGSL shader; below that it is layered strokes.
 *
 * @param level live 0..1 signal (framing quality / voice rhythm / mic RMS depending on [state]).
 * @param contentDescription spoken by TalkBack as a polite live region, e.g. "Listening".
 */
@Composable
fun Halo(
    state: HaloState,
    modifier: Modifier = Modifier,
    level: () -> Float = { 0f },
    contentDescription: String? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val colors = Vm.colors
    val reduce = LocalReduceMotion.current
    val currentLevel by rememberUpdatedState(level)

    // Slow-changing look: springs (or a short fade under reduce-motion). Interruptible by construction.
    val base = HaloSpec.base(state)
    val floatSpec = if (reduce) Motion.reducedFade<Float>() else Motion.gentle<Float>()
    val radius by animateFloatAsState(base.radius, floatSpec, label = "radius")
    val thickness by animateFloatAsState(base.thickness, floatSpec, label = "thickness")
    val glow by animateFloatAsState(base.glow, floatSpec, label = "glow")
    val quartz by animateFloatAsState(base.quartzMix, floatSpec, label = "quartz")
    val shimmer by animateFloatAsState(base.shimmer, floatSpec, label = "shimmer")
    val ripples by animateFloatAsState(base.ripples, floatSpec, label = "ripples")
    val warmth by animateFloatAsState(base.warmth, floatSpec, label = "warmth")

    val phases = if (reduce) StaticPhases else rememberLoopPhases()

    // Ready: a brief bloom plus a tiny overshoot "settle". Under reduce-motion only the bloom (brightness).
    val bloom = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state, reduce) {
        if (state == HaloState.Ready) {
            bloom.snapTo(1f)
            if (!reduce) {
                pop.snapTo(1.07f)
                launch { pop.animateTo(1f, Motion.settle()) }
            }
            bloom.animateTo(0f, tween(900))
        } else {
            bloom.animateTo(0f, tween(200))
            pop.snapTo(1f)
        }
    }

    val glowShader = remember { if (Build.VERSION.SDK_INT >= 33) HaloGlowShader() else null }

    Box(
        modifier = modifier
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        liveRegion = LiveRegionMode.Polite
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val half = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                onDrawBehind {
                    val lvl = currentLevel()
                    val live = HaloSpec.live(state, base, lvl, phases.breath.value, reduce)
                    // `radius` etc. are the spring-animated values; `live` adds the per-frame wobble.
                    val liveRadius = radius + (live.radius - base.radius)
                    val liveGlow = glow * (live.glow / base.glow.coerceAtLeast(0.01f))
                    val r = half * liveRadius * pop.value
                    val stroke = half * thickness
                    val brass = lerp(colors.brass, colors.champagne, warmth)
                    val ringColor = lerp(brass, colors.quartz, quartz)
                    val highlight = lerp(colors.champagne, colors.quartz, quartz)

                    // 1. Bloom disc (Ready) and a faint inner fill for depth.
                    if (bloom.value > 0.01f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(colors.champagne.copy(alpha = 0.40f * bloom.value), Color.Transparent),
                                center = center, radius = r * 1.35f,
                            ),
                            radius = r * 1.35f, center = center,
                        )
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(ringColor.copy(alpha = 0.05f + 0.05f * liveGlow), Color.Transparent),
                            center = center, radius = r,
                        ),
                        radius = r, center = center,
                    )

                    // 2. Glow: AGSL on 33+, layered strokes below.
                    if (glowShader != null && Build.VERSION.SDK_INT >= 33) {
                        glowShader.update(
                            cx = center.x, cy = center.y, radius = r, thickness = stroke,
                            glow = liveGlow + bloom.value * 0.6f,
                            angle = phases.angle.value, shimmer = shimmer,
                            colorA = ringColor, colorB = highlight,
                        )
                        drawRect(brush = glowShader.brush)
                    } else {
                        for (i in 1..5) {
                            drawCircle(
                                color = ringColor.copy(alpha = ((0.16f * (liveGlow + bloom.value * 0.6f)) / i).coerceIn(0f, 1f)),
                                radius = r, center = center,
                                style = Stroke(width = stroke + i * half * 0.05f),
                            )
                        }
                    }

                    // 3. Outward ripples (Listening). Strength follows the mic level.
                    if (ripples > 0.01f && !reduce) {
                        for (k in 0 until 3) {
                            val p = (phases.ripple.value + k / 3f) % 1f
                            val rr = r + p * (half * 0.98f - r).coerceAtLeast(0f)
                            val a = (1f - p) * (1f - p) * 0.55f * ripples * (0.35f + 0.65f * lvl)
                            drawCircle(
                                color = colors.quartz.copy(alpha = a),
                                radius = rr, center = center,
                                style = Stroke(width = stroke * 0.5f),
                            )
                        }
                    }

                    // 4. The crisp ring itself.
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(ringColor, lerp(ringColor, highlight, 0.55f), ringColor),
                            center = center,
                        ),
                        radius = r, center = center,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )

                    // 5. Shimmer highlight riding on the ring (Analysing).
                    if (shimmer > 0.01f) {
                        val deg = Math.toDegrees(phases.angle.value.toDouble()).toFloat()
                        drawArc(
                            color = highlight.copy(alpha = 0.9f * shimmer),
                            startAngle = deg - 28f, sweepAngle = 56f, useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r),
                            size = Size(r * 2, r * 2),
                            style = Stroke(width = stroke * 1.15f, cap = StrokeCap.Round),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content?.invoke(this)
    }
}
