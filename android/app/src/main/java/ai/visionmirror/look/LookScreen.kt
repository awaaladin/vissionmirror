package ai.visionmirror.look

import ai.visionmirror.camera.LookCamera
import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.MirrorFrame
import ai.visionmirror.design.components.PermissionCard
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.ui.ScreenScaffold
import ai.visionmirror.ui.tapAnywhere
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Read / Colour / Look around: the back camera with a three-second hold-steady countdown, then the answer is spoken
 * and shown in large type. Tap anywhere to look again.
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

    ScreenScaffold {
        if (!hasCamera) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                PermissionCard(
                    VmIcons.Capture, "Camera needed",
                    "I need the camera to ${mode.title.lowercase()} for you.",
                    "Allow camera", { cameraLauncher.launch(Manifest.permission.CAMERA) },
                )
            }
            return@ScreenScaffold
        }

        Column(
            Modifier.fillMaxSize().tapAnywhere(viewModel::onTap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(VmIcons.Back, "Back to home", onBack)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    BasicText(mode.title, style = Vm.type.label.copy(color = Vm.colors.bone))
                }
                Halo(
                    state = if (state.busy) HaloState.Analysing else HaloState.Idle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            }

            StatusCaption(state.caption, announce = false, modifier = Modifier.fillMaxWidth())

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MirrorFrame(
                    modifier = Modifier.fillMaxWidth(0.9f).aspectRatio(0.75f),
                    glow = { if (state.busy) 1f else 0.3f },
                ) {
                    LookCamera(viewModel.captureController)
                    state.countdown?.let { n ->
                        BasicText(
                            n.toString(),
                            style = Vm.type.numeric.copy(color = Vm.colors.champagne, fontSize = 120.sp, lineHeight = 130.sp),
                        )
                    }
                }
            }

            PrimaryAction(
                text = "Look again",
                onClick = viewModel::onTap,
                modifier = Modifier.fillMaxWidth(),
                icon = VmIcons.Capture,
                enabled = !state.busy,
            )
            GhostAction("Back to home", onBack, Modifier.fillMaxWidth())
        }
    }
}
