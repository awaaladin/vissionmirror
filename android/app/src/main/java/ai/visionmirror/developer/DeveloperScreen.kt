package ai.visionmirror.developer

import ai.visionmirror.BuildConfig
import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.SettingGroup
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.ui.ScreenScaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Tools for checking that the app really works on this phone. Every button here does something real:
 * it calls the live server, speaks with the saved voice, or changes saved settings. (Design Lab is the
 * one exception: it is a visual specimen sheet, labelled as such.)
 */
@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    onOpenLab: () -> Unit,
    viewModel: DeveloperViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconAction(VmIcons.Back, "Back", onBack)
                BasicText("Developer tools", style = Vm.type.headline.copy(color = Vm.colors.bone))
            }

            SettingGroup("This build") {
                Fact("Version", BuildConfig.VERSION_NAME)
                Fact("Build type", if (BuildConfig.DEBUG) "debug" else "release")
                Fact("Server", BuildConfig.API_BASE_URL)
                Fact("Phone voice engine", ui.voiceInfo)
            }

            SettingGroup("Server") {
                BasicText(
                    "Calls the live server's health check, so you know the phone can reach it without taking a photo.",
                    style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                )
                GhostAction(
                    if (ui.checking) "Checking…" else "Check connection",
                    viewModel::checkConnection,
                    Modifier.fillMaxWidth(),
                    icon = VmIcons.Offline,
                    enabled = !ui.checking,
                )
                ui.connectionResult?.let {
                    BasicText(it, style = Vm.type.body.copy(color = Vm.colors.bone))
                }
            }

            SettingGroup("Voice") {
                GhostAction("Speak a test sentence", viewModel::speakTest, Modifier.fillMaxWidth(), icon = VmIcons.Speaker)
            }

            SettingGroup("Saved settings") {
                GhostAction("Show the welcome tour next time", viewModel::replayOnboarding, Modifier.fillMaxWidth(), icon = VmIcons.Repeat)
                GhostAction("Reset all settings", viewModel::resetSettings, Modifier.fillMaxWidth(), icon = VmIcons.Retake)
                ui.note?.let { BasicText(it, style = Vm.type.body.copy(color = Vm.colors.bone)) }
            }

            SettingGroup("Appearance reference") {
                BasicText(
                    "A sheet of the colours, icons and components, for checking the design. Its buttons only demonstrate how things look and sound.",
                    style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                )
                GhostAction("Open Design Lab", onOpenLab, Modifier.fillMaxWidth(), icon = VmIcons.Palette)
            }

            GhostAction("Turn off developer tools", { viewModel.turnOff(); onBack() }, Modifier.fillMaxWidth(), icon = VmIcons.Close)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = Vm.type.label.copy(color = Vm.colors.boneMuted))
        BasicText(value, style = Vm.type.body.copy(color = Vm.colors.bone), modifier = Modifier.fillMaxWidth())
    }
}

