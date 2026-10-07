package ai.visionmirror.session

import ai.visionmirror.design.components.ErrorState
import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.MirrorFrame
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.ui.ScreenScaffold
import ai.visionmirror.ui.sharedHalo
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Halo shimmers around the frozen photo while the description is fetched. A skeleton of the
 * result card hints at what is coming. Moves on by itself as soon as there is something to show.
 */
@Composable
fun AnalysingScreen(
    session: SessionViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.phase) {
        if (state.phase == Phase.Result || state.phase == Phase.Unusable) onDone()
    }

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        ) {
            when (state.phase) {
                Phase.Failed -> ErrorState(
                    title = "I couldn't describe that",
                    message = state.banner ?: "Please try again.",
                    retryLabel = "Try again",
                    onRetry = session::retry,
                )
                else -> {
                    val offline = state.phase == Phase.Offline
                    Halo(
                        state = if (offline) HaloState.Idle else HaloState.Analysing,
                        contentDescription = if (offline) "Waiting for internet" else "Looking at you",
                        modifier = Modifier.sharedHalo().size(280.dp),
                    ) { PhotoInHalo(state.photo) }

                    StatusCaption(
                        text = when {
                            offline -> "No internet. I'll describe you as soon as you're back online."
                            else -> "Looking at you…"
                        },
                        announce = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!offline) SkeletonCard()
                }
            }
            GhostAction("Cancel", onClick = onBack, icon = VmIcons.Back)
        }
    }
}

/** The captured still inside the Halo, in the same soft oval as the live mirror. */
@Composable
fun PhotoInHalo(photo: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    MirrorFrame(modifier = modifier.size(width = 150.dp, height = 200.dp)) {
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Your photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Placeholder bars that pulse slowly (opacity only). Static under reduce-motion. */
@Composable
private fun SkeletonCard() {
    val colors = Vm.colors
    val reduce = Vm.reduceMotion
    // Read in the draw lambda below, so the pulse never recomposes the card.
    val pulse = if (reduce) {
        null
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            0.35f, 0.7f,
            infiniteRepeatable(tween(1_400, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "pulse",
        )
    }
    val shape = Radius.shape(Radius.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(Spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        listOf(1f, 0.85f, 0.6f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(18.dp)
                    .drawBehind { drawRoundRect(colors.hairline.copy(alpha = pulse?.value ?: 0.5f), cornerRadius = CornerRadius(9.dp.toPx())) },
            )
        }
    }
}
