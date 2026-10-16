package ai.visionmirror.home

import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.WakeWord
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.PressableSurface
import ai.visionmirror.design.components.StatusCaption
import ai.visionmirror.design.components.VmIcon
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.session.AskPhase
import ai.visionmirror.session.Phase
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.ui.ScreenScaffold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private class Shortcut(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Things to try, shown one at a time under the orb so she learns what she can ask without a manual. */
private val Hints = listOf(
    "“Adanna, read this”",
    "“Adanna, what colour is this?”",
    "“Adanna, look around”",
    "“Adanna, how do I look?”",
    "“Adanna, make the text bigger”",
    "“Adanna, speak slower”",
    "“Adanna, brighter”",
    "“Adanna, what can I say?”",
)

/**
 * Home is a conversation, not a menu: one big living orb that is Adanna. Tap it, or say her name, and talk. It listens
 * (ripples), speaks (pulses) and thinks (shimmers), and what she says appears under it in large type. The four tools
 * sit in a quiet dock at the bottom for when she would rather touch than talk.
 */
@Composable
fun HomeScreen(
    session: SessionViewModel,
    speech: SpeechManager,
    onMirror: () -> Unit,
    onRead: () -> Unit,
    onColour: () -> Unit,
    onLook: () -> Unit,
    onSettings: () -> Unit,
    onGreet: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Coming home forgets the last photo, like entering the mirror does.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                session.clear()
                onGreet()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val ui by session.state.collectAsStateWithLifecycle()
    val spoken by speech.state.collectAsStateWithLifecycle()

    val haloState = when {
        ui.ask == AskPhase.Listening -> HaloState.Listening
        ui.ask == AskPhase.Thinking || ui.phase == Phase.Analysing -> HaloState.Analysing
        spoken.isSpeaking -> HaloState.Speaking
        else -> HaloState.Idle
    }

    var hint by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(6_000)
            hint = (hint + 1) % Hints.size
        }
    }

    val shortcuts = listOf(
        Shortcut("Mirror", VmIcons.Mirror, onMirror),
        Shortcut("Read", VmIcons.Detail, onRead),
        Shortcut("Colour", VmIcons.Palette, onColour),
        Shortcut("Around", VmIcons.Eye, onLook),
    )

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("VisionMirror", style = Vm.type.title.copy(color = Vm.colors.champagne))
                Box(Modifier.weight(1f))
                IconAction(VmIcons.Settings, "Settings", onSettings)
            }

            // The orb takes the middle of the screen. Tapping it is the same as saying her name.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.82f)
                        .aspectRatio(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (ui.ask == AskPhase.Listening) {
                                "${WakeWord.NAME} is listening. Double tap to stop."
                            } else {
                                "Talk to ${WakeWord.NAME}. Double tap, then speak."
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = session::toggleListening,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Halo(
                        state = haloState,
                        level = { if (haloState == HaloState.Speaking) 0.6f else session.micLevel.value },
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // What she is saying (highlighted as it is read), or something to try.
            Box(Modifier.fillMaxWidth().padding(bottom = Spacing.md), contentAlignment = Alignment.Center) {
                when {
                    ui.ask == AskPhase.Listening ->
                        StatusCaption(ui.partialQuestion.ifBlank { "I'm listening..." }, announce = false, modifier = Modifier.fillMaxWidth())
                    spoken.text.isNotBlank() ->
                        StatusCaption(spoken.text, highlight = spoken.wordRange, announce = false, modifier = Modifier.fillMaxWidth())
                    else -> BasicText(
                        Hints[hint],
                        style = Vm.type.bodyLarge.copy(color = Vm.colors.boneMuted, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // The dock: four quiet round buttons with their names under them.
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                shortcuts.forEach { ShortcutView(it) }
            }
        }
    }
}

@Composable
private fun ShortcutView(shortcut: Shortcut) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        PressableSurface(
            onClick = shortcut.onClick,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            contentDescription = shortcut.label,
        ) {
            VmIcon(shortcut.icon, tint = Vm.colors.champagne)
        }
        BasicText(shortcut.label, style = Vm.type.body.copy(color = Vm.colors.boneMuted))
    }
}
