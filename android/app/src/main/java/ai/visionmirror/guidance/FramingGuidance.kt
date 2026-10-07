package ai.visionmirror.guidance

import kotlin.math.abs

/**
 * A face seen by the camera, in **preview space**: upright, mirrored like the front-camera
 * preview (so "right" is her right as she looks at the screen), every value 0..1 of the frame.
 */
data class FaceObservation(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    companion object {
        /**
         * @param left/top/right/bottom pixel box in the upright (rotation-corrected) image
         * @param mirror true for the front camera: the preview is flipped horizontally
         */
        fun fromBox(
            left: Float, top: Float, right: Float, bottom: Float,
            imageWidth: Float, imageHeight: Float,
            mirror: Boolean,
        ): FaceObservation {
            val cx = (left + right) / 2f / imageWidth
            return FaceObservation(
                centerX = if (mirror) 1f - cx else cx,
                centerY = (top + bottom) / 2f / imageHeight,
                width = (right - left) / imageWidth,
                height = (bottom - top) / imageHeight,
            )
        }

        /** With several faces, coach the largest (the nearest person: the one holding the phone). */
        fun largest(boxes: List<FaceObservation>): FaceObservation? = boxes.maxByOrNull { it.width * it.height }
    }
}

/** What to tell her. [good] means the framing is right and capture may begin. */
enum class Guidance(val spoken: String, val good: Boolean = false) {
    NoFace("I can't see your face. Hold the phone at arm's length, facing you."),
    StepBack("Step back a little."),
    StepCloser("Move a little closer."),
    MoveLeft("Move a little left."),
    MoveRight("Move a little right."),
    RaisePhone("Raise the phone a little."),
    LowerPhone("Lower the phone a little."),
    Perfect("Perfect, hold still.", good = true),
}

/** The verdict for one frame. [quality] 0..1 drives haptic tick speed, the Halo and the frame glow. */
data class Framing(val guidance: Guidance, val quality: Float)

/**
 * Frames the head and upper torso, centred, at a target face size.
 *
 * Why these numbers: a face about 20% of the frame height, centred horizontally with its centre
 * about a third of the way down, leaves roughly the upper body in view below it, which is what the
 * AI needs to describe a top. Pure Kotlin so it is unit-tested without a camera.
 */
class FramingGuidance(
    private val targetFaceHeight: Float = 0.20f,
    private val sizeTolerance: Float = 0.05f,
    private val targetCenterX: Float = 0.50f,
    private val xTolerance: Float = 0.08f,
    private val targetCenterY: Float = 0.32f,
    private val yTolerance: Float = 0.07f,
) {
    fun evaluate(face: FaceObservation?): Framing {
        if (face == null) return Framing(Guidance.NoFace, 0f)

        val dSize = face.height - targetFaceHeight
        val dx = face.centerX - targetCenterX
        val dy = face.centerY - targetCenterY

        // Quality: how far from ideal, each axis scaled to its own forgiving range.
        val es = (abs(dSize) / 0.15f).coerceAtMost(1f)
        val ex = (abs(dx) / 0.25f).coerceAtMost(1f)
        val ey = (abs(dy) / 0.25f).coerceAtMost(1f)
        val raw = 1f - (0.40f * es + 0.35f * ex + 0.25f * ey)

        // Fix the biggest problem first: distance, then side to side, then up/down.
        val guidance = when {
            dSize > sizeTolerance -> Guidance.StepBack
            dSize < -sizeTolerance -> Guidance.StepCloser
            // In the mirrored preview, a face on the right means she should move to her left.
            dx > xTolerance -> Guidance.MoveLeft
            dx < -xTolerance -> Guidance.MoveRight
            // A face low in the frame means the camera points too high.
            dy > yTolerance -> Guidance.LowerPhone
            dy < -yTolerance -> Guidance.RaisePhone
            else -> Guidance.Perfect
        }
        // Not-yet-perfect framing never reports full quality, so "perfect" is always distinct.
        val quality = if (guidance.good) 1f else raw.coerceIn(0f, 0.95f)
        return Framing(guidance, quality)
    }
}
