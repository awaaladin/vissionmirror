package ai.visionmirror.look

import ai.visionmirror.camera.LookCamera
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.PermissionCard
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.ui.ScreenScaffold
import ai.visionmirror.ui.tapAnywhere
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Read / Colour / Look around: the back camera fills the whole screen, so she can take in everything it sees. A bright
 * line sweeps over the picture while it looks, then the answer is spoken and shown. Tap anywhere to look again.
 * (The mirror, which is about her own reflection, keeps its oval; these look at the world, so they use all of it.)
 */
@Composable
fun LookScreen(
    mode: LookMode,
    session: SessionViewModel,
    onDescribed: () -> Unit,
    onBack: () -> Unit,
    viewModel: LookViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    DisposableEffect(lifecycle, hasCamera, mode) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (hasCamera) viewModel.onEnter(mode)
                Lifecycle.Event.ON_PAUSE -> viewModel.onExit()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onExit()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            if (e is LookEvent.Describe) {
                session.submitPhoto(e.photo.jpeg, e.photo.bitmap, scene = true)
                onDescribed()
            }
        }
    }

    if (!hasCamera) {
        ScreenScaffold {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                PermissionCard(
                    VmIcons.Capture, "Camera needed",
                    "I need the camera to ${mode.title.lowercase()} for you.",
                    "Allow camera", { cameraLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Vm.colors.ink).tapAnywhere(session::talkFromCamera)) {
        LookCamera(viewModel.captureController)
        ScanSweep(active = state.scanning || state.busy)

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(VmIcons.Back, "Back to home", onBack)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(Radius.shape(Radius.lg)).background(Vm.colors.ink.copy(alpha = 0.7f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) { BasicText(mode.title, style = Vm.type.label.copy(color = Vm.colors.bone)) }
            }
            Box(Modifier.padding(start = 64.dp)) // balances the back button so the title stays centred
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.md)
                .clip(Radius.shape(Radius.lg)).background(Vm.colors.ink.copy(alpha = 0.82f)).padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StatusCaption(state.caption, announce = false, modifier = Modifier.fillMaxWidth())
            PrimaryAction(
                text = "Look again",
                onClick = viewModel::onTap,
                modifier = Modifier.fillMaxWidth(),
                icon = VmIcons.Capture,
                enabled = !state.busy,
            )
        }
    }
}

/** A bright line that sweeps down the picture while it is being looked at: it should feel like scanning, and be quick. */
@Composable
private fun ScanSweep(active: Boolean) {
    if (!active) return
    val line = Vm.colors.champagne
    val progress = if (Vm.reduceMotion) {
        0.5f
    } else {
        val t = rememberInfiniteTransition(label = "scan")
        val p by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "scanY",
        )
        p
    }
    Canvas(Modifier.fillMaxSize()) {
        val y = size.height * progress
        val band = size.height * 0.18f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, line.copy(alpha = 0.30f)),
                startY = y - band,
                endY = y,
            ),
            topLeft = Offset(0f, y - band),
            size = androidx.compose.ui.geometry.Size(size.width, band),
        )
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 4.dp.toPx())
    }
}
