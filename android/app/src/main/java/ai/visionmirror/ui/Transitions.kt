package ai.visionmirror.ui

import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.design.tokens.Motion
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The Halo is one persistent element across screens: this key makes it morph (position, size)
 * between destinations instead of cutting. A gentle spring drives the bounds.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedHalo(): Modifier {
    val transition = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedScope.current ?: return this
    return with(transition) {
        this@sharedHalo.sharedElement(
            sharedContentState = rememberSharedContentState(key = "halo"),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> Motion.gentle<Rect>() },
        )
    }
}

/** Reads one field of a State without recomposing the caller on unrelated changes. */
@Composable
fun <T, R> State<T>.select(transform: (T) -> R): State<R> = remember(this) { derivedStateOf { transform(value) } }

/** The word range to highlight inside [caption] when the speaker is reading a part of it. */
fun SpeechManager.State.highlightIn(caption: String): IntRange? {
    val range = wordRange ?: return null
    if (!isSpeaking || text.isBlank()) return null
    val offset = caption.indexOf(text)
    if (offset < 0) return null
    return (range.first + offset)..(range.last + offset)
}

/**
 * Press and hold anywhere (that nothing else handles) to talk. [onStart] fires after the system
 * long-press delay, [onEnd] on release. A quick tap does nothing here.
 */
fun Modifier.holdToTalk(onStart: () -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(onStart, onEnd) {
        val timeout = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            awaitFirstDown() // unconsumed only: buttons keep their own taps
            // Lifting the finger, or a scroll taking over (cancellation), before the timeout is not a hold.
            val isHold = try {
                withTimeout(timeout) { waitForUpOrCancellation() }
                false
            } catch (_: PointerEventTimeoutCancellationException) {
                true
            }
            if (isHold) {
                onStart()
                waitForUpOrCancellation()
                onEnd()
            }
        }
    }

/**
 * Whole-screen tap target for sighted and low-vision users. It deliberately adds no semantics:
 * TalkBack users get a real labelled button on every screen that uses this. Taps that a child
 * (a button) already handled are ignored.
 */
fun Modifier.tapAnywhere(onTap: () -> Unit): Modifier =
    pointerInput(onTap) { detectTapGestures { onTap() } }
