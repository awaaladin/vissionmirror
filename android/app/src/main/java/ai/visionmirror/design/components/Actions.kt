package ai.visionmirror.design.components

import ai.visionmirror.design.tokens.Elevation
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tinted, decorative icon. Pass a description only when the icon is the sole content. */
@Composable
fun VmIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Vm.colors.bone,
    size: Dp = 28.dp,
) {
    Image(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * The one main action on a screen. Big, soft, brass. Callers normally give it `fillMaxWidth()`.
 * 72 dp tall (the preferred touch size is 64 dp).
 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = Vm.colors
    PressableSurface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 72.dp),
        shape = Radius.shape(Radius.lg),
        brush = colors.brassRamp,
        border = if (colors.isHighContrast) BorderStroke(2.dp, colors.bone) else null,
        elevation = Elevation.medium,
        enabled = enabled,
        contentDescription = contentDescription ?: text,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) VmIcon(icon, tint = colors.onBrass)
            BasicText(text, style = Vm.type.label.copy(color = colors.onBrass))
        }
    }
}

/** Secondary action: hairline outline, no fill. */
@Composable
fun GhostAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = Vm.colors
    PressableSurface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Spacing.preferredTouch),
        shape = Radius.shape(Radius.lg),
        color = Color.Transparent,
        border = BorderStroke(if (colors.isHighContrast) 2.dp else 1.dp, if (colors.isHighContrast) colors.bone else colors.hairline),
        enabled = enabled,
        contentDescription = contentDescription ?: text,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) VmIcon(icon, tint = colors.bone)
            BasicText(text, style = Vm.type.label.copy(color = colors.bone))
        }
    }
}

/** Round, icon-only. [contentDescription] is mandatory: this is how a blind user knows what it does. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Spacing.preferredTouch,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = Vm.colors
    PressableSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = if (selected) colors.brass else colors.raised,
        border = BorderStroke(if (colors.isHighContrast) 2.dp else 1.dp, if (selected) colors.champagne else colors.hairline),
        enabled = enabled,
        contentDescription = contentDescription,
    ) {
        VmIcon(icon, tint = if (selected) colors.onBrass else colors.bone)
    }
}

