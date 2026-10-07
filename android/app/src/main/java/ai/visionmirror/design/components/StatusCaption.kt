package ai.visionmirror.design.components

import ai.visionmirror.design.tokens.Vm
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Large caption for state changes and the spoken result.
 *
 * With [highlight] (from `SpeechManager.State.wordRange`) the word being spoken gets a brass block
 * behind it, words already spoken stay bone and upcoming words dim. The highlight changes colour
 * and background only, never weight or size, so the text never reflows while it is read.
 *
 * Set [announce] = false while TTS is reading the same text, so TalkBack does not talk over it.
 */
@Composable
fun StatusCaption(
    text: String,
    modifier: Modifier = Modifier,
    highlight: IntRange? = null,
    announce: Boolean = true,
    style: TextStyle = Vm.type.bodyLarge,
) {
    val colors = Vm.colors
    val annotated = remember(text, highlight, colors) {
        buildCaption(text, highlight, colors.bone, colors.boneMuted, colors.brass, colors.onBrass)
    }
    BasicText(
        text = annotated,
        style = style.copy(color = colors.bone),
        modifier = modifier.semantics {
            if (announce) liveRegion = LiveRegionMode.Polite
        },
    )
}

internal fun buildCaption(
    text: String,
    highlight: IntRange?,
    spoken: androidx.compose.ui.graphics.Color,
    upcoming: androidx.compose.ui.graphics.Color,
    markBackground: androidx.compose.ui.graphics.Color,
    markText: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    if (highlight == null) {
        append(text)
        return@buildAnnotatedString
    }
    val start = highlight.first.coerceIn(0, text.length)
    val end = (highlight.last + 1).coerceIn(start, text.length)
    withStyle(SpanStyle(color = spoken)) { append(text.substring(0, start)) }
    withStyle(SpanStyle(color = markText, background = markBackground)) { append(text.substring(start, end)) }
    withStyle(SpanStyle(color = upcoming)) { append(text.substring(end)) }
}
