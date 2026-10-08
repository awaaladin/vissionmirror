package ai.visionmirror.mirror

import ai.visionmirror.camera.CameraPreview
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.MirrorFrame
import ai.visionmirror.design.components.PermissionCard
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.ui.ScreenScaffold
import ai.visionmirror.ui.select
import ai.visionmirror.ui.sharedHalo
import ai.visionmirror.ui.tapAnywhere
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The mirror: front camera inside a soft oval, spoken guidance, haptic ticks that speed up as she
 * centres, then an auto-capture countdown. Tap anywhere (or press a volume key) to capture now;
 * tap during the countdown to cancel it.
 *
 * Note: face boxes are normalised against the full camera image while the oval crops it, so the
 * guidance targets are tuned to this crop; verify on a device.
 */
@Composable
fun MirrorScreen(
    session: SessionViewModel,
    onCaptured: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MirrorViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stateHolder = viewModel.state.collectAsStateWithLifecycle()

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    // Entering the mirror always starts fresh: this is also where a previous photo is forgotten.
    DisposableEffect(lifecycle, hasCamera) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (hasCamera) {
                    session.clear()
                    viewModel.onEnter()
                }
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
            if (e is MirrorEvent.Captured) {
                session.submitPhoto(e.photo.jpeg, e.photo.bitmap)
                onCaptured()
            }
        }
    }

    ScreenScaffold {
        if (!hasCamera) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                PermissionCard(
                    VmIcons.Capture, "Camera needed",
                    "I need the front camera to guide you and take your photo.",
                    "Allow camera", { cameraLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
            return@ScreenScaffold
        }

        // Read only the fields each part needs, so 15 camera frames a second don't recompose everything.
        val caption by stateHolder.select { it.caption }
        val countdown by stateHolder.select { it.countdown }
        val capturing by stateHolder.select { it.capturing }
        val faceVisible by stateHolder.select { it.faceVisible }
        val good by stateHolder.select { it.guidance?.good == true }
        val paused by stateHolder.select { it.paused }
        val facing by stateHolder.select { it.facing }

        val haloState = when {
            capturing -> HaloState.Analysing
            paused -> HaloState.Idle
            countdown != null || good -> HaloState.Ready
            faceVisible -> HaloState.Guiding
            else -> HaloState.Idle
        }

        Column(
            Modifier.fillMaxSize().tapAnywhere(viewModel::onTap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Halo(
                    state = haloState,
                    level = { stateHolder.value.quality },
                    contentDescription = null,
                    modifier = Modifier.sharedHalo().size(88.dp),
                )
                Box(Modifier.weight(1f))
                IconAction(
                    VmIcons.Flip,
                    if (facing == CameraFacing.Front) "Switch to the back camera" else "Switch to the front camera",
                    viewModel::flipCamera,
                )
                IconAction(
                    if (paused) VmIcons.Play else VmIcons.Pause,
                    if (paused) "Resume guidance" else "Pause guidance",
                    viewModel::togglePause,
                )
                IconAction(VmIcons.Settings, "Settings", onOpenSettings)
            }

            // The spoken line, in large type. The speaker already reads it, so no live region.
            StatusCaption(caption, announce = false, modifier = Modifier.fillMaxWidth())

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MirrorFrame(
                    modifier = Modifier.fillMaxWidth(0.9f).aspectRatio(0.75f),
                    glow = { stateHolder.value.quality },
                ) {
                    CameraPreview(
                        controller = viewModel.captureController,
                        onFace = viewModel::onFace,
                        facing = facing,
                    )
                    countdown?.let { n -> Countdown(n) }
                }
            }

            PrimaryAction(
                text = if (countdown != null) "Cancel countdown" else "Take photo",
                onClick = viewModel::onTap,
                modifier = Modifier.fillMaxWidth(),
                icon = if (countdown != null) VmIcons.Close else VmIcons.Capture,
            )
            BasicText(
                "Or tap anywhere, or press a volume key.",
                style = Vm.type.body.copy(color = Vm.colors.boneMuted),
            )
        }
    }
}

/** Big tabular numeral that pops in with a spring on each tick (the tone and haptic land with it). */
@Composable
private fun Countdown(n: Int) {
    val reduce = Vm.reduceMotion
    key(n) {
        val scale = remember { Animatable(if (reduce) 1f else 1.4f) }
        LaunchedEffect(Unit) { if (!reduce) scale.animateTo(1f, Motion.settle()) }
        BasicText(
            text = n.toString(),
            style = Vm.type.numeric.copy(color = Vm.colors.champagne, fontSize = 120.sp, lineHeight = 130.sp),
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        )
    }
}
