package ai.visionmirror.guide

import ai.visionmirror.camera.GuideCamera
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Guide mode: the back camera full screen, and the latest thing she said in large type. Tap anywhere to talk. */
@Composable
fun GuideScreen(
    session: SessionViewModel,
    onBack: () -> Unit,
    viewModel: GuideViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    DisposableEffect(lifecycle, hasCamera) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (hasCamera) viewModel.onEnter()
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

    if (!hasCamera) {
        ScreenScaffold {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                PermissionCard(
                    VmIcons.Capture, "Camera needed",
                    "I need the camera to guide you.",
                    "Allow camera", { cameraLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Vm.colors.ink).tapAnywhere(session::talkFromCamera)) {
        GuideCamera(onFrame = viewModel::onFrame)

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(VmIcons.Back, "Back to home", onBack)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(Radius.shape(Radius.lg)).background(Vm.colors.ink.copy(alpha = 0.7f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                ) { BasicText("Guide me", style = Vm.type.label.copy(color = Vm.colors.bone)) }
            }
            Box(Modifier.padding(start = 64.dp))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.md)
                .clip(Radius.shape(Radius.lg)).background(Vm.colors.ink.copy(alpha = 0.82f)).padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StatusCaption(state.caption, announce = false, modifier = Modifier.fillMaxWidth())
            BasicText(
                "An extra pair of eyes, not a replacement for your cane or guide. I can't see steps, holes or glass.",
                style = Vm.type.body.copy(color = Vm.colors.boneMuted),
            )
            PrimaryAction(
                text = if (state.paused) "Carry on" else "Pause",
                onClick = viewModel::togglePause,
                modifier = Modifier.fillMaxWidth(),
                icon = if (state.paused) VmIcons.Play else VmIcons.Pause,
            )
        }
    }
}
