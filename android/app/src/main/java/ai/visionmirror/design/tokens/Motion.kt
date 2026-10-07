package ai.visionmirror.design.tokens

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion principles (see project brief): springs for anything spatial, interruptible always,
 * transform + alpha only, nothing faster than ~3 Hz, no decorative loops except the Halo's breath.
 *
 * These numbers are a first pass and must be tuned by feel on a real device.
 */
object Motion {
    /** Press-down is fast and tight (~90 ms); release uses [press] for a springy return. */
    fun <T> pressDown(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 2200f)

    /** Springy release with a little overshoot. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 700f)

    /** Default for panels, indicators and anything medium-sized. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.75f, stiffness = 380f)

    /** Large surfaces and the Halo: slow, soft, barely overshoots. */
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)

    /** Dismissals and dense UI: no overshoot. */
    fun <T> critical(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 600f)

    /** Opacity is not spatial, so a short tween is fine. Never ease-in. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(durationMillis = 160)

    /** Reduce-motion replacement for springs: brightness/opacity change only. */
    fun <T> reducedFade(): FiniteAnimationSpec<T> = tween(durationMillis = 120)

    object Duration {
        const val STAGGER_STEP = 70 // ms between children
        const val STAGGER_CAP = 600 // ms total, so nothing keeps the user waiting
        const val BREATH = 4200 // ms per Halo breath (~0.24 Hz)
        const val SHIMMER = 2600 // ms per shimmer revolution (~0.38 Hz)
        const val RIPPLE = 1700 // ms per listening ripple (~0.6 Hz)
    }

    fun staggerDelay(index: Int): Int = (index * Duration.STAGGER_STEP).coerceAtMost(Duration.STAGGER_CAP)
}

/** Picks this spec normally, or an instant/near-instant alternative when motion is reduced. */
fun <T> FiniteAnimationSpec<T>.forMotion(
    reduce: Boolean,
    reduced: FiniteAnimationSpec<T> = snap(),
): FiniteAnimationSpec<T> = if (reduce) reduced else this

/** True when the app's own toggle OR the system "remove animations" setting asks for less motion. */
val LocalReduceMotion = compositionLocalOf { false }

/** `ANIMATOR_DURATION_SCALE == 0` is how Android expresses "remove animations". */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}
