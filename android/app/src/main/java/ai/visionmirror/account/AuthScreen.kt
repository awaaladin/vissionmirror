package ai.visionmirror.account

import ai.visionmirror.R
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Sign in or create an account. This is the first screen most people meet, and it is usually a sighted helper (a friend or
 * family member) who fills it in, so it is laid out like any good sign-in: brand on top, one clear choice, labelled
 * fields, mistakes explained right under the field, and a clear way to carry on without an account.
 *
 * Never a dead end: "Continue as guest" is always there, because the app works without an account.
 * [firstRun] is true when this is the gate shown after the tour.
 */
@Composable
fun AuthScreen(
    firstRun: Boolean,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val signUp = ui.mode == AuthMode.SignUp
    val errors = AuthValidation.check(ui.mode, ui.email, ui.password, ui.confirm)

    LaunchedEffect(Unit) { viewModel.announce(firstRun) }
    LaunchedEffect(ui.done) { if (ui.done) onDone() }

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (onBack != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { IconAction(VmIcons.Back, "Back", onBack) }
            }

            // Brand
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(84.dp))
                BasicText("VisionMirror", style = Vm.type.headline.copy(color = Vm.colors.bone))
                BasicText(
                    if (firstRun) "Sign in, or create an account, to get started." else "Your account",
                    style = Vm.type.body.copy(color = Vm.colors.boneMuted, textAlign = TextAlign.Center),
                )
            }

            SegmentedControl(
                options = AuthMode.entries.map { it.label },
                selectedIndex = ui.mode.ordinal,
                onSelect = { viewModel.setMode(AuthMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (signUp) {
                VmTextField(ui.name, viewModel::setName, "Your name (optional)", onImeAction = { focus.moveFocus(FocusDirection.Down) })
            }

            VmTextField(
                ui.email, viewModel::setEmail, "Email",
                keyboardType = KeyboardType.Email,
                onImeAction = { focus.moveFocus(FocusDirection.Down) },
            )
            FieldNote(errors.email.takeIf { ui.submitted }, error = true)

            VmTextField(
                value = ui.password,
                onValueChange = viewModel::setPassword,
                label = "Password",
                secret = true,
                showSecret = ui.showPassword,
                imeAction = if (signUp) ImeAction.Next else ImeAction.Done,
                onImeAction = { if (signUp) focus.moveFocus(FocusDirection.Down) else viewModel.submit() },
            )
            if (signUp) {
                // The rule is always visible; it turns to an error only after she has tried to continue.
                val shortOnEnter = ui.password.length < AuthValidation.MIN_PASSWORD
                FieldNote(
                    if (shortOnEnter) "Use at least ${AuthValidation.MIN_PASSWORD} characters." else "Good: ${ui.password.length} characters.",
                    error = shortOnEnter && ui.submitted,
                    good = !shortOnEnter,
                )
            } else {
                FieldNote(errors.password.takeIf { ui.submitted }, error = true)
            }

            if (signUp) {
                VmTextField(
                    value = ui.confirm,
                    onValueChange = viewModel::setConfirm,
                    label = "Type the password again",
                    secret = true,
                    showSecret = ui.showPassword,
                    imeAction = ImeAction.Done,
                    onImeAction = viewModel::submit,
                )
                FieldNote(
                    when {
                        ui.confirm.isEmpty() -> errors.confirm.takeIf { ui.submitted }
                        errors.confirm != null -> errors.confirm
                        else -> "The passwords match."
                    },
                    error = errors.confirm != null && (ui.submitted || ui.confirm.isNotEmpty()),
                    good = ui.confirm.isNotEmpty() && errors.confirm == null,
                )
            }

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
                "Setting this up for someone else? Use your own email and look after the password for them. " +
                    "An account keeps only an email and a scrambled password. Photos are never stored.",
                style = Vm.type.body.copy(color = Vm.colors.boneMuted),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** One short line under a field: an error in ember, a confirmation in champagne, or a quiet hint. */
@Composable
private fun FieldNote(text: String?, error: Boolean = false, good: Boolean = false) {
    if (text == null) return
    val colors = Vm.colors
    BasicText(
        text,
        style = Vm.type.body.copy(color = if (error) colors.ember else if (good) colors.champagne else colors.boneMuted),
        modifier = Modifier.padding(start = 4.dp),
    )
}
