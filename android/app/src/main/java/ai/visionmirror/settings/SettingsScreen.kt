package ai.visionmirror.settings

import ai.visionmirror.BuildConfig
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.SegmentedControl
import ai.visionmirror.design.components.SettingGroup
import ai.visionmirror.design.components.SettingToggleRow
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.FontChoice
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.ThemeMode
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.ui.ScreenScaffold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Everything is a large row with a clear label. Spoken-rate, detail and text-size choices are words,
 * not numbers. At large font scales the segmented controls stack instead of clipping, and the whole
 * screen scrolls. Every change is saved at once and is still there next time the app opens.
 */
@Composable
fun SettingsScreen(
    session: SessionViewModel,
    speaker: Speaker,
    onBack: () -> Unit,
    onOpenVoices: () -> Unit,
    onOpenDeveloper: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconAction(VmIcons.Back, "Back", onBack)
                BasicText("Settings", style = Vm.type.headline.copy(color = Vm.colors.bone))
            }

            SettingGroup("Text") {
                BasicText("Text size", style = Vm.type.label.copy(color = Vm.colors.bone))
                SegmentedControl(
                    options = TextSize.entries.map { it.label },
                    selectedIndex = TextSize.nearest(s.textScale).ordinal,
                    onSelect = { viewModel.setTextSize(TextSize.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                BasicText("Font", style = Vm.type.label.copy(color = Vm.colors.bone))
                SegmentedControl(
                    options = FontChoice.entries.map { it.label },
                    selectedIndex = s.fontChoice.ordinal,
                    onSelect = { viewModel.setFontChoice(FontChoice.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // A live sample, so the effect of both choices is visible straight away.
                BasicText(
                    "Your navy blue shirt looks neat. Il1 O0 rn m",
                    style = Vm.type.bodyLarge.copy(color = Vm.colors.boneMuted),
                )
            }

            SettingGroup("Voice") {
                BasicText("Speech speed", style = Vm.type.label.copy(color = Vm.colors.bone))
                SegmentedControl(
                    options = SpeechRate.entries.map { it.label },
                    selectedIndex = SpeechRate.nearest(s.speechRate).ordinal,
                    onSelect = { viewModel.setSpeechRate(SpeechRate.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostAction(
                    "Voice: ${viewModel.currentVoiceLabel()}",
                    onOpenVoices,
                    Modifier.fillMaxWidth(),
                    icon = VmIcons.Speaker,
                    contentDescription = "Voice: ${viewModel.currentVoiceLabel()}. Double tap to choose another voice.",
                )
                SettingToggleRow(
                    "Voice commands",
                    s.voiceCommands,
                    viewModel::setVoiceCommands,
                    description = "After I read your result, listen for “ask”, “retake” or “repeat”.",
                )
            }

            SettingGroup("Description") {
                BasicText("Default detail", style = Vm.type.label.copy(color = Vm.colors.bone))
                SegmentedControl(
                    options = DetailLevel.entries.map { it.label },
                    selectedIndex = s.detailLevel.ordinal,
                    onSelect = { viewModel.setDetail(DetailLevel.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingGroup("Camera") {
                BasicText("Which camera", style = Vm.type.label.copy(color = Vm.colors.bone))
                SegmentedControl(
                    options = CameraFacing.entries.map { it.label },
                    selectedIndex = s.cameraFacing.ordinal,
                    onSelect = { viewModel.setCameraFacing(CameraFacing.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                BasicText(
                    "You can also switch on the Mirror screen. Guidance works best with the front camera.",
                    style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                )
            }

            SettingGroup("Feel") {
                SettingToggleRow("Haptics", s.haptics, viewModel::setHaptics, description = "Vibration for taps and framing.")
                SettingToggleRow(
                    "Reduce motion", s.reduceMotion, viewModel::setReduceMotion,
                    description = "Calmer screen. Brightness changes instead of movement.",
                )
            }

            SettingGroup("Look") {
                BasicText("Theme", style = Vm.type.label.copy(color = Vm.colors.bone))
                SegmentedControl(
                    options = ThemeMode.entries.map { it.label },
                    selectedIndex = s.themeMode.ordinal,
                    onSelect = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingToggleRow(
                    "High contrast", s.highContrast, viewModel::setHighContrast,
                    description = "Pure black, white and yellow.",
                )
            }

            SettingGroup("Privacy") {
                GhostAction("Replay privacy statement", viewModel::replayPrivacy, Modifier.fillMaxWidth(), icon = VmIcons.Lock)
                GhostAction(
                    "Delete session data",
                    {
                        session.clear()
                        speaker.say("Deleted. Nothing from your last photo remains.", Priority.Interrupt)
                    },
                    Modifier.fillMaxWidth(),
                    icon = VmIcons.Close,
                )
            }

            if (BuildConfig.DEBUG || s.developerMode) {
                SettingGroup("Developer") {
                    GhostAction("Developer tools", onOpenDeveloper, Modifier.fillMaxWidth(), icon = VmIcons.Tool)
                }
            }

            // Seven taps here turn on Developer tools (in release builds), like Android's own setting.
            BasicText(
                "VisionMirror ${BuildConfig.VERSION_NAME}",
                style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                modifier = Modifier
                    .defaultMinSize(minHeight = Spacing.preferredTouch)
                    .clickable(role = Role.Button) { viewModel.onVersionTapped()?.let(viewModel::say) },
            )
        }
    }
}
