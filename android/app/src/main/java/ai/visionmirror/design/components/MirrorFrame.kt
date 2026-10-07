package ai.visionmirror.design.components

import ai.visionmirror.design.tokens.LocalReduceMotion
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Vm
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import java.util.Random

/** A true ellipse (RoundedCornerShape(50%) would give a stadium). */
val OvalShape = GenericShape { size, _ -> addOval(Rect(0f, 0f, size.width, size.height)) }

/**
 * The camera frame: a soft oval with an animated brass edge. [glow] is the framing quality (0..1),
 * read in the draw phase: the edge brightens and thickens as she gets better centred.
 * Under reduce-motion the edge is static and only its brightness follows [glow].
 */
@Composable
fun MirrorFrame(
    modifier: Modifier = Modifier,
    glow: () -> Float = { 0f },
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = Vm.colors
    val reduce = LocalReduceMotion.current
    val angle: State<Float> = if (reduce) {
        remember { mutableStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "frame").animateFloat(
            0f, 360f, infiniteRepeatable(tween(Motion.Duration.SHIMMER * 3, easing = LinearEasing), RepeatMode.Restart),
            label = "frameAngle",
        )
    }
    Box(
        modifier = modifier
            .clip(OvalShape)
            .background(colors.surface)
            .grain()
            .drawWithContent {
                drawContent()
                val g = glow().coerceIn(0f, 1f)
                val w = (3.dp.toPx() + 3.dp.toPx() * g)
                val tl = Offset(w / 2, w / 2)
                val sz = Size(size.width - w, size.height - w)
                val edge = lerp(colors.brass, colors.champagne, g)
                // Soft inner glow: wider, fainter strokes stacked inside the edge.
                for (i in 3 downTo 1) {
                    drawOval(
                        color = edge.copy(alpha = (0.10f + 0.18f * g) / i),
                        topLeft = tl, size = sz,
                        style = Stroke(width = w + i * 7.dp.toPx()),
                    )
                }
                drawOval(
                    brush = Brush.sweepGradient(
                        listOf(edge, colors.champagne.copy(alpha = 0.55f + 0.45f * g), edge),
                        center = center,
                    ),
                    topLeft = tl, size = sz, style = Stroke(width = w),
                )
                // Travelling bright spot on the edge (static position under reduce-motion).
                val rad = Math.toRadians(angle.value.toDouble())
                val spot = Offset(
                    center.x + (sz.width / 2) * kotlin.math.cos(rad).toFloat(),
                    center.y + (sz.height / 2) * kotlin.math.sin(rad).toFloat(),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(colors.champagne.copy(alpha = 0.55f * (0.4f + 0.6f * g)), androidx.compose.ui.graphics.Color.Transparent),
                        center = spot, radius = 36.dp.toPx(),
                    ),
                    radius = 36.dp.toPx(), center = spot,
                )
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
        content = content,
    )
}

// ---- grain -------------------------------------------------------------------------------------

/** 96x96 warm noise tile, generated once and tiled: a texture, not a runtime shader. */
private fun makeGrain(rgb: Int): ImageBitmap {
    val n = 96
    val rnd = Random(42)
    val px = IntArray(n * n) { (rnd.nextInt(256) shl 24) or rgb } // random alpha, fixed tint
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private val grainOnDark: ImageBitmap by lazy { makeGrain(0xEFE9DD) } // warm bone specks
private val grainOnLight: ImageBitmap by lazy { makeGrain(0x3A2E1A) } // warm ink specks

/** Very subtle tactile grain for large surfaces. Off in High Contrast (pure black stays pure). */
@Composable
fun Modifier.grain(alpha: Float = 0.045f): Modifier {
    if (Vm.colors.isHighContrast) return this
    val light = Vm.colors.isLight
    val brush = remember(light) {
        ShaderBrush(ImageShader(if (light) grainOnLight else grainOnDark, TileMode.Repeated, TileMode.Repeated) as Shader)
    }
    return this.drawBehind { drawRect(brush = brush, alpha = alpha) }
}
