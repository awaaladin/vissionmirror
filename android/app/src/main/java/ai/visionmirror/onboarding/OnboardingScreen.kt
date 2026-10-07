package ai.visionmirror.onboarding

import ai.visionmirror.audio.Speaker
import ai.visionmirror.design.components.PermissionCard
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.components.VmIcon
import ai.visionmirror.design.components.reveal
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.ui.ScreenScaffold
import ai.visionmirror.ui.highlightIn
import ai.visionmirror.ui.sharedHalo
import ai.visionmirror.ui.tapAnywhere
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * First launch. The Halo animates in and breathes; the welcome and privacy statement are spoken;
 * each permission is explained aloud before its system dialog. The whole screen is one huge
 * Continue target (plus a visible button and the spoken word "continue").
 */
@Composable
fun OnboardingScreen(
    speaker: Speaker,
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speech by speaker.state.collectAsStateWithLifecycle()

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onCameraResult(it)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onMicrophoneResult(it)
    }

    LaunchedEffect(Unit) {
        viewModel.onEnter()
        viewModel.events.collect { e ->
            when (e) {
                OnboardingEvent.RequestCamera -> cameraLauncher.launch(Manifest.permission.CAMERA)
                OnboardingEvent.RequestMicrophone -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                OnboardingEvent.Finished -> onFinished()
            }
        }
    }

    ScreenScaffold {
        // Anywhere you tap continues; the visible Continue button is the labelled target for TalkBack.
        Column(
            Modifier.fillMaxSize().tapAnywhere(viewModel::onContinue).verticalScroll(rememberScrollState()).padding(vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xl, Alignment.CenterVertically),
        ) {
            Halo(
                state = if (speech.isSpeaking) HaloState.Speaking else HaloState.Idle,
                level = { if (speech.isSpeaking) 0.5f else 0f },
                contentDescription = "VisionMirror",
                modifier = Modifier.sharedHalo().size(260.dp),
            ) { VmIcon(VmIcons.Mirror, tint = Vm.colors.bone, size = 64.dp) }

            when (state.step) {
                OnboardingStep.Camera -> PermissionCard(
                    VmIcons.Capture, "Camera", "I use the front camera to guide you and take your photo.",
                    "Continue", viewModel::onContinue, Modifier.reveal(),
                )
                OnboardingStep.Microphone -> PermissionCard(
                    VmIcons.Mic, "Microphone", "I only listen when you ask me a question.",
                    "Continue", viewModel::onContinue, Modifier.reveal(),
                )
                else -> {
                    StatusCaption(
                        text = state.caption,
                        highlight = speech.highlightIn(state.caption),
                        announce = false, // the speaker is already reading it
                        modifier = Modifier.reveal(),
                    )
                    PrimaryAction(
                        text = if (state.step == OnboardingStep.Done) "Let's go" else "Continue",
                        onClick = viewModel::onContinue,
                        modifier = Modifier.fillMaxWidth(),
                        icon = VmIcons.Check,
                    )
                }
            }
        }
    }
}
