package ai.visionmirror.home

import ai.visionmirror.audio.WakeWord
import ai.visionmirror.design.components.IconAction
import ai.visionmirror.design.components.PressableSurface
import ai.visionmirror.design.components.VmIcon
import ai.visionmirror.design.halo.Halo
import ai.visionmirror.design.halo.HaloState
import ai.visionmirror.design.icons.VmIcons
import ai.visionmirror.design.tokens.Radius
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.ui.ScreenScaffold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private class Tile(val label: String, val hint: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Home: Adanna, and four big things she can do. Everything here can also be asked for by voice
 * ("Adanna, read this"), so the tiles are the touch version of the same list.
 */
@Composable
fun HomeScreen(
    session: SessionViewModel,
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

    val tiles = listOf(
        Tile("Mirror", "How do I look", VmIcons.Mirror, onMirror),
        Tile("Read", "Labels, signs, papers", VmIcons.Detail, onRead),
        Tile("Colour", "What colour is this", VmIcons.Palette, onColour),
        Tile("Look around", "What is in front of me", VmIcons.Eye, onLook),
    )

    ScreenScaffold {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                IconAction(VmIcons.Settings, "Settings", onSettings)
            }
            Halo(
                state = HaloState.Idle,
                contentDescription = null,
                modifier = Modifier.size(132.dp),
            )
            BasicText(WakeWord.NAME, style = Vm.type.display.copy(color = Vm.colors.bone))
            BasicText(
                "Say “${WakeWord.NAME}” and ask for what you need.",
                style = Vm.type.bodyLarge.copy(color = Vm.colors.boneMuted),
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        pair.forEach { tile -> TileView(tile, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileView(tile: Tile, modifier: Modifier) {
    PressableSurface(
        onClick = tile.onClick,
        modifier = modifier.fillMaxSize(),
        shape = Radius.shape(Radius.lg),
        contentDescription = "${tile.label}. ${tile.hint}.",
    ) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.md),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VmIcon(tile.icon, tint = Vm.colors.champagne, size = 40.dp)
            Box(Modifier.size(Spacing.xs))
            BasicText(tile.label, style = Vm.type.label.copy(color = Vm.colors.bone))
            BasicText(tile.hint, style = Vm.type.body.copy(color = Vm.colors.boneMuted))
        }
    }
}
