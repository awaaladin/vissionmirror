package ai.visionmirror.design.components

import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Explains a permission aloud-friendly and in large type, before the system dialog appears. */
@Composable
fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Vm.colors
    val shape = Radius.shape(Radius.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        VmIcon(icon, tint = colors.brass, size = 40.dp)
        BasicText(title, style = Vm.type.headline.copy(color = colors.bone))
        BasicText(body, style = Vm.type.body.copy(color = colors.boneMuted))
        // Inner button radius is concentric with the card (card radius minus card padding).
        PrimaryAction(actionLabel, onAction, Modifier.fillMaxWidth())
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Vm.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        VmIcon(icon, tint = colors.boneMuted, size = 48.dp)
        BasicText(title, style = Vm.type.title.copy(color = colors.bone, textAlign = TextAlign.Center))
        BasicText(body, style = Vm.type.body.copy(color = colors.boneMuted, textAlign = TextAlign.Center))
        action?.invoke()
    }
}

/** Failure with a way out: words + icon (never colour alone) and a retry. */
@Composable
fun ErrorState(
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Vm.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl).semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        VmIcon(VmIcons.Warning, tint = colors.ember, size = 48.dp)
        BasicText(title, style = Vm.type.title.copy(color = colors.bone, textAlign = TextAlign.Center))
        BasicText(message, style = Vm.type.body.copy(color = colors.boneMuted, textAlign = TextAlign.Center))
        PrimaryAction(retryLabel, onRetry, Modifier.fillMaxWidth(), icon = VmIcons.Retake)
    }
}

/** The on-screen twin of a spoken error ("I can't reach the internet..."). Scales in from its top edge. */
@Composable
fun SpokenErrorBanner(
    message: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = Vm.colors
    val shape = Radius.shape(Radius.lg)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.fade()) + scaleIn(Motion.settle(), initialScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0f)),
        exit = fadeOut(Motion.fade()) + scaleOut(Motion.critical(), targetScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.raised, shape)
                .border(1.dp, colors.ember, shape)
                .padding(Spacing.md)
                .semantics { liveRegion = LiveRegionMode.Assertive },
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VmIcon(VmIcons.Warning, tint = colors.ember)
            BasicText(message, style = Vm.type.body.copy(color = colors.bone), modifier = Modifier.weight(1f))
            if (actionLabel != null) GhostAction(actionLabel, onAction)
        }
    }
}

/** Full-screen "I'm listening" moment: dimmed scrim, big Quartz Halo rippling with the mic level. */
@Composable
fun ListeningOverlay(
    level: () -> Float,
    modifier: Modifier = Modifier,
    caption: String = "Listening. Let go when you're done.",
) {
    val colors = Vm.colors
    Box(
        modifier = modifier.fillMaxSize().background(colors.ink.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Halo(
                state = HaloState.Listening,
                level = level,
                contentDescription = "Listening",
                modifier = Modifier.size(320.dp),
            ) { VmIcon(VmIcons.Mic, tint = colors.quartz, size = 48.dp) }
            BasicText(caption, style = Vm.type.title.copy(color = colors.bone, textAlign = TextAlign.Center))
        }
    }
}
