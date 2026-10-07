package ai.visionmirror.audio

import ai.visionmirror.design.tokens.LocalReduceMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Turns word boundaries from [SpeechManager] into a 0..1 "voice rhythm" signal for the Halo.
 * Returned as a lambda so the Halo reads it in its draw phase and nothing recomposes per word.
 *
 * Each word kicks the level up, then it eases back, so the ring pulses in time with the voice
 * without exceeding ~3 beats per second.
 */
@Composable
fun rememberSpeechLevel(speech: Speaker): () -> Float {
    val level = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(speech, reduce) {
        speech.state
            .map { if (it.isSpeaking) it.wordRange?.first ?: -1 else -2 }
            .distinctUntilChanged()
            .collect { word ->
                when {
                    word == -2 -> level.animateTo(0f, tween(240))
                    word == -1 -> level.animateTo(0.35f, tween(160))
                    reduce -> level.animateTo(0.5f, tween(160))
                    else -> {
                        level.animateTo(0.85f, spring(dampingRatio = 0.8f, stiffness = 900f))
                        level.animateTo(0.3f, tween(260))
                    }
                }
            }
    }
    return { level.value }
}
