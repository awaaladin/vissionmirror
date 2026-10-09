package ai.visionmirror.account

import ai.visionmirror.design.components.GhostAction
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.PrimaryAction
import ai.visionmirror.design.components.SegmentedControl
import ai.visionmirror.design.components.SpokenErrorBanner
import ai.visionmirror.design.components.VmTextField
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.ui.ScreenScaffold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Sign in or create an account. Never a dead end: "Continue as guest" is always there, because the
 * mirror works without an account. [firstRun] is true when this is the gate shown after the tour.
 */
@Composable
fun AuthScreen(
    firstRun: Boolean,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.announce(firstRun) }
    LaunchedEffect(ui.done) { if (ui.done) onDone() }

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (onBack != null) IconAction(VmIcons.Back, "Back", onBack)
                BasicText(
                    if (firstRun) "Welcome" else "Account",
                    style = Vm.type.headline.copy(color = Vm.colors.bone),
                )
            }

            SegmentedControl(
                options = AuthMode.entries.map { it.label },
                selectedIndex = ui.mode.ordinal,
                onSelect = { viewModel.setMode(AuthMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (ui.mode == AuthMode.SignUp) {
                VmTextField(ui.name, viewModel::setName, "Your name (optional)")
            }
            VmTextField(ui.email, viewModel::setEmail, "Email", keyboardType = KeyboardType.Email)
            VmTextField(
                value = ui.password,
                onValueChange = viewModel::setPassword,
                label = if (ui.mode == AuthMode.SignUp) "Password (at least 8 characters)" else "Password",
                secret = true,
                showSecret = ui.showPassword,
                imeAction = ImeAction.Done,
                onImeAction = viewModel::submit,
            )
            GhostAction(
                if (ui.showPassword) "Hide password" else "Show password",
                viewModel::toggleShowPassword,
                Modifier.fillMaxWidth(),
            )

            SpokenErrorBanner(message = ui.error.orEmpty(), visible = ui.error != null)

            PrimaryAction(
                text = if (ui.busy) "Please wait…" else ui.mode.label,
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.busy,
                icon = VmIcons.Person,
            )

            GhostAction(
                if (firstRun) "Continue as guest" else "Not now",
                { if (firstRun) viewModel.continueAsGuest() else onBack?.invoke() ?: onDone() },
                Modifier.fillMaxWidth(),
            )

            BasicText(
                "An account keeps only your email and a scrambled password. Your photos are still never stored.",
                style = Vm.type.body.copy(color = Vm.colors.boneMuted),
            )
        }
    }
}
