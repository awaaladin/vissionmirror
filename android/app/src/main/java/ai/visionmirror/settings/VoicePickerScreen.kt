package ai.visionmirror.settings

import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.ui.ScreenScaffold
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Pick the voice the app speaks with. Each tap saves the choice and plays a sample. The list is the
 * offline voices the phone already has; more can be installed in the phone's text-to-speech settings.
 */
@Composable
fun VoicePickerScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val voices = remember { viewModel.voices() }

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconAction(VmIcons.Back, "Back", onBack)
                BasicText("Choose a voice", style = Vm.type.headline.copy(color = Vm.colors.bone))
            }
            BasicText(
                "Tap a voice to hear it. Your choice is saved.",
                style = Vm.type.body.copy(color = Vm.colors.boneMuted),
            )

            VoiceRow("Phone default", selected = s.voiceName == null) { viewModel.chooseVoice(null) }
            voices.forEach { v ->
                VoiceRow(v.label, selected = s.voiceName == v.name) { viewModel.chooseVoice(v.name) }
            }

            if (voices.isEmpty()) {
                BasicText(
                    "This phone has no other offline voices installed, or they are still loading. " +
                        "You can install more in the phone's text-to-speech settings.",
                    style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                )
            }

            GhostAction(
                "Install more voices",
                {
                    // Opens the phone's own text-to-speech settings, where voices are downloaded.
                    val intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                        .onFailure { viewModel.say("I couldn't open the phone's voice settings. Look for Text-to-speech in Settings.") }
                },
                Modifier.fillMaxWidth(),
                icon = VmIcons.Speaker,
            )
        }
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    GhostAction(
        text = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = if (selected) VmIcons.Check else null,
        contentDescription = if (selected) "$label, selected" else label,
    )
}
