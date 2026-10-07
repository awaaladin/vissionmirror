package ai.visionmirror.ui

import ai.visionmirror.design.components.grain
import ai.visionmirror.design.tokens.Spacing
import ai.visionmirror.design.tokens.Vm
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Ink background with a whisper of grain, safe-area insets and the standard 24 dp gutter. */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Vm.colors.ink)
            .grain()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = Spacing.lg),
    ) { content() }
}
