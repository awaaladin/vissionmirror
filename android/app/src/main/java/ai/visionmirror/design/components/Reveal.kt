package ai.visionmirror.design.components

import ai.visionmirror.design.tokens.LocalReduceMotion
import ai.visionmirror.design.tokens.Motion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Entrance choreography: scale 0.95 + alpha 0 -> rest, from [origin] (not from nothing, not from
 * the centre). Children stagger by ~70 ms, capped at 600 ms total, in meaning order.
 * Transform and alpha only. Reduce motion: a short fade, no delay, no movement.
 */
fun Modifier.reveal(
    index: Int = 0,
    visible: Boolean = true,
    origin: TransformOrigin = TransformOrigin(0.5f, 0f),
): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val rise = with(LocalDensity.current) { 12.dp.toPx() }
    val progress = remember { Animatable(if (visible) 0f else 1f) }
    LaunchedEffect(visible, reduce) {
        if (visible && !reduce) delay(Motion.staggerDelay(index).toLong())
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = if (reduce) tween(120) else Motion.settle(),
        )
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        if (!reduce) {
            val s = 0.95f + 0.05f * p
            scaleX = s
            scaleY = s
            translationY = (1f - p) * rise
            transformOrigin = origin
        }
    }
}

