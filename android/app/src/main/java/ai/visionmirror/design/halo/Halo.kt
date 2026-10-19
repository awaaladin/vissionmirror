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
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.Path
import kotlin.math.min
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.launch

private const val LINES = 8
private const val SEGMENTS = 96

/** Loop phases for the always-on animations. Absent entirely under reduce-motion. */
@Stable
private class LoopPhases(
    val breath: State<Float>,
    val angle: State<Float>,
    val ripple: State<Float>,
    /** A slow loop for the ripple lines: one turn in 9 seconds, faster while she speaks via the line speeds. */
    val wave: State<Float>,
)

private val StaticPhases = LoopPhases(mutableStateOf(0f), mutableStateOf(0f), mutableStateOf(0f), mutableStateOf(0f))

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
    val wave = t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "wave",
    )
    return remember(breath, angle, ripple, wave) { LoopPhases(breath, angle, ripple, wave) }
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
    val rippleBase by animateFloatAsState(
        when (state) {
            HaloState.Idle -> 0.012f
            HaloState.Guiding -> 0.020f
            HaloState.Listening -> 0.026f
            HaloState.Analysing -> 0.020f
            HaloState.Ready -> 0.016f
            HaloState.Speaking -> 0.060f
        },
        floatSpec, label = "rippleBase",
    )
    val rippleLevel = if (state == HaloState.Speaking) 0.05f else if (state == HaloState.Listening) 0.05f else 0.01f

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

                    // Bloom (Ready): a soft flash that fades, nothing else fills the circle, so what is behind shows through.
                    if (bloom.value > 0.01f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.Transparent, colors.champagne.copy(alpha = 0.30f * bloom.value), Color.Transparent),
                                center = center, radius = r * 1.3f,
                            ),
                            radius = r * 1.3f, center = center,
                        )
                    }

                    // The ripple: several thin, flowing lines round a hollow circle. They stay calm when idle, move with
                    // her voice when she listens, and ripple hard while the assistant speaks. The wave speeds are whole
                    // multiples of the loop angle, so the animation repeats without a seam.
                    val voice = if (state == HaloState.Speaking) 0.7f + 0.3f * sin(2f * phases.angle.value) else 1f
                    val amp = (rippleBase + rippleLevel * lvl) * voice * (1f + bloom.value * 0.5f)
                    val t = phases.wave.value * (if (state == HaloState.Speaking) 3f else if (state == HaloState.Listening || state == HaloState.Analysing) 2f else 1f)
                    val lineWidth = (half * 0.011f).coerceAtLeast(1f)
                    val path = Path()
                    for (q in 0 until LINES) {
                        val dir = if (q % 2 == 0) 1 else -1
                        val k1 = 2 + q % 3; val k2 = 3 + q % 4; val k3 = 5 + q % 3
                        val m1 = dir * (1 + q % 2); val m2 = -dir * (1 + (q + 1) % 3); val m3 = dir * (1 + (q + 2) % 3)
                        val ph1 = q * 0.9f; val ph2 = q * 1.7f + 1f; val ph3 = q * 0.6f + 2f
                        val base = r * (0.955f + q * 0.012f)
                        path.rewind()
                        for (j in 0..SEGMENTS) {
                            val th = j * (2f * PI.toFloat() / SEGMENTS)
                            val off = sin(k1 * th + m1 * t + ph1) / 1.4f + sin(k2 * th + m2 * t + ph2) / 2.4f + sin(k3 * th + m3 * t + ph3) / 3.4f
                            val rad = base * (1f + amp * off * (1f + q * 0.1f))
                            val x = center.x + cos(th) * rad
                            val y = center.y + sin(th) * rad
                            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        val color = if (q % 2 == 0) ringColor else highlight
                        val alpha = (0.34f + (q % 3) * 0.12f) * (0.75f + 0.25f * liveGlow.coerceIn(0f, 1.5f))
                        drawPath(path, color.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(width = lineWidth))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content?.invoke(this)
    }
}
