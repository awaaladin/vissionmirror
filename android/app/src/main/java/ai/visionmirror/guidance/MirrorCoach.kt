package ai.visionmirror.guidance

/**
 * Limits how often she is spoken to: at most one instruction per [minGapMs], and never the same
 * one back to back. If she is stuck on one instruction for [repeatAfterMs] it may be said again
 * (otherwise silence would feel like the app had given up).
 */
class SpeechThrottle(
    private val minGapMs: Long = 1_500,
    private val repeatAfterMs: Long = 8_000,
) {
    private var lastText: String? = null
    private var lastAt: Long = Long.MIN_VALUE / 2

    /** Returns [text] if it may be spoken now, and records it; otherwise null. */
    fun offer(text: String, nowMs: Long): String? {
        val gap = nowMs - lastAt
        val allowed = gap >= minGapMs && (text != lastText || gap >= repeatAfterMs)
        if (!allowed) return null
        lastText = text
        lastAt = nowMs
        return text
    }

    fun reset() {
        lastText = null
        lastAt = Long.MIN_VALUE / 2
    }
}

/** Framing must stay good for [requiredMs] without a break before we auto-capture. */
class StabilityTracker(private val requiredMs: Long = 1_200) {
    private var goodSince: Long? = null

    fun update(good: Boolean, nowMs: Long): Boolean {
        if (!good) {
            goodSince = null
            return false
        }
        val since = goodSince ?: nowMs.also { goodSince = it }
        return nowMs - since >= requiredMs
    }

    fun reset() { goodSince = null }
}

/** Everything the screen should do after one camera frame. */
data class CoachOutput(
    val framing: Framing,
    /** Sentence to speak now, already throttled. Null means stay quiet. */
    val speak: String?,
    /** Framing has been good long enough: start the countdown. */
    val stable: Boolean,
    /** Quality rose a clear step since the last cue: play the "closer" earcon. */
    val closerCue: Boolean,
)

/** Ties guidance, throttling, stability and cues together. Pure: time is passed in. */
class MirrorCoach(
    private val guidance: FramingGuidance = FramingGuidance(),
    private val throttle: SpeechThrottle = SpeechThrottle(),
    private val stability: StabilityTracker = StabilityTracker(),
    private val cueStep: Float = 0.2f,
) {
    private var lastCueQuality = 0f

    fun update(face: FaceObservation?, nowMs: Long): CoachOutput {
        val framing = guidance.evaluate(face)
        val speak = throttle.offer(framing.guidance.spoken, nowMs)
        val stable = stability.update(framing.guidance.good, nowMs)

        var cue = false
        if (framing.quality - lastCueQuality >= cueStep && !framing.guidance.good) {
            cue = true
            lastCueQuality = framing.quality
        } else if (framing.quality < lastCueQuality - cueStep) {
            lastCueQuality = framing.quality
        }
        return CoachOutput(framing, speak, stable, cue)
    }

    /** Call after a capture or cancel so the next attempt starts fresh. */
    fun reset() {
        throttle.reset()
        stability.reset()
        lastCueQuality = 0f
    }
}
