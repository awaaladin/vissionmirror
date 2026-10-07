package ai.visionmirror.design.components

import ai.visionmirror.design.tokens.Elevation
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.design.tokens.tintedShadow
import ai.visionmirror.haptics.LocalHaptics
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Base for everything tappable. No Material ripple: the surface physically gives.
 *
 * - Press-down: fast, tight spring to [pressedScale] plus a haptic tick (the tick lands as the
 *   surface bottoms out, so it feels like one event).
 * - Release: a different, springier spec with a little overshoot.
 * - Interruptible: both are `animateFloatAsState`, so tapping again mid-spring keeps velocity.
 * - Reduce motion: no scale at all; the surface dims instead. Feedback is kept, movement is not.
 * - Transform and alpha only, read inside `graphicsLayer {}`, so pressing never recomposes.
 */
@Composable
fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Radius.shape(Radius.md),
    color: Color = Vm.colors.raised,
    brush: Brush? = null,
    border: BorderStroke? = BorderStroke(1.dp, Vm.colors.hairline),
    elevation: Dp = Elevation.none,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    contentDescription: String? = null,
    hapticOnPress: Boolean = true,
    pressedScale: Float = 0.96f,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Vm.colors
    val reduce = Vm.reduceMotion
    val haptics = LocalHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()

    LaunchedEffect(pressed) { if (pressed && hapticOnPress && enabled) haptics.tick() }

    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) pressedScale else 1f,
        animationSpec = if (pressed) Motion.pressDown() else Motion.press(),
        label = "pressScale",
    )
    val dim by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.45f
            pressed && reduce -> 0.75f
            else -> 1f
        },
        animationSpec = Motion.reducedFade(),
        label = "pressDim",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            }
            .tintedShadow(elevation, shape, colors.shadowTint)
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(if (focused) Modifier.border(3.dp, colors.champagne, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = role,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .sizeIn(minWidth = Spacing.minTouch, minHeight = Spacing.minTouch),
        contentAlignment = contentAlignment,
        content = content,
    )
}
