package ai.visionmirror.design.halo

import androidx.compose.runtime.Immutable

/**
 * What the Halo is telling the user. One state at a time; the Halo morphs between them with springs.
 * Per-frame signals (framing quality, voice rhythm, mic RMS) are NOT part of the state: they are
 * passed to [Halo] as a lambda so they never trigger recomposition.
 */
sealed interface HaloState {
    /** Slow breathing pulse in Brass. */
    data object Idle : HaloState

    /** Ring tightens and brightens as framing improves (level = framing quality 0..1). */
    data object Guiding : HaloState

    /** Pulses with the voice (level = rhythm from [ai.visionmirror.audio.rememberSpeechLevel]). */
    data object Speaking : HaloState

    /** Ripples outward in Quartz (level = recogniser RMS 0..1). */
    data object Listening : HaloState

    /** A soft shimmer sweeps around the ring. */
    data object Analysing : HaloState

    /** Settles with a brief bloom. */
    data object Ready : HaloState
}

/** Slow-changing look of a state. Springs animate between these. */
@Immutable
data class HaloBase(
    /** Ring radius as a fraction of half the smaller dimension. */
    val radius: Float,
    /** Stroke width as a fraction of the same. */
    val thickness: Float,
    val glow: Float,
    /** 0 = Brass, 1 = Quartz. */
    val quartzMix: Float,
    /** 0..1 strength of the sweeping highlight. */
    val shimmer: Float,
    /** 0..1 strength of outward ripples. */
    val ripples: Float,
    /** 0 = Brass, 1 = Champagne. */
    val warmth: Float,
)

/** Per-frame modulation of a [HaloBase]. */
@Immutable
data class HaloLive(val radius: Float, val glow: Float)

object HaloSpec {
    fun base(state: HaloState): HaloBase = when (state) {
        HaloState.Idle -> HaloBase(0.80f, 0.030f, 0.55f, 0f, 0f, 0f, 0f)
        HaloState.Guiding -> HaloBase(0.80f, 0.034f, 0.70f, 0f, 0f, 0f, 0.3f)
        HaloState.Speaking -> HaloBase(0.80f, 0.032f, 0.75f, 0f, 0f, 0f, 0.2f)
        HaloState.Listening -> HaloBase(0.76f, 0.030f, 0.70f, 1f, 0f, 1f, 0f)
        HaloState.Analysing -> HaloBase(0.78f, 0.028f, 0.70f, 0f, 1f, 0f, 0.1f)
        HaloState.Ready -> HaloBase(0.80f, 0.036f, 0.95f, 0f, 0f, 0f, 1f)
    }

    /**
     * @param level 0..1 live signal for this state
     * @param breath 0..1 slow breathing phase (Idle only)
     * @param reduceMotion keep brightness changes but drop all size changes
     */
    fun live(state: HaloState, base: HaloBase, level: Float, breath: Float, reduceMotion: Boolean): HaloLive {
        val l = level.coerceIn(0f, 1f)
        val radiusOffset = when (state) {
            HaloState.Idle -> 0.012f * breath
            HaloState.Guiding -> -0.10f * l // tightens as framing improves
            HaloState.Speaking -> 0.030f * l
            HaloState.Listening -> 0.015f * l
            else -> 0f
        }
        val glowBoost = when (state) {
            HaloState.Idle -> 0.35f * breath
            HaloState.Guiding -> 0.60f * l
            HaloState.Speaking -> 0.80f * l
            HaloState.Listening -> 0.50f * l
            else -> 0f
        }
        return HaloLive(
            radius = base.radius + if (reduceMotion) 0f else radiusOffset,
            glow = base.glow * (1f + glowBoost),
        )
    }
}
