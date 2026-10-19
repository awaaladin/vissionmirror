package ai.visionmirror.guide

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * A very cheap way to tell whether the view has changed, so Guide mode can look at the scene every ten seconds or so without
 * asking the AI about the same corridor again and again. The picture is shrunk to a small grid of brightness values.
 */
object SceneChange {
    const val COLUMNS = 16
    const val ROWS = 12

    /** The picture as a [COLUMNS] x [ROWS] grid of brightness values, 0..255. */
    fun lumaOf(picture: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(picture, COLUMNS, ROWS, true)
        val pixels = IntArray(COLUMNS * ROWS)
        small.getPixels(pixels, 0, COLUMNS, 0, 0, COLUMNS, ROWS)
        if (small !== picture) small.recycle()
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
    }

    /**
     * How different two views are, 0 (the same) to 1 (nothing alike). The overall brightness is removed first, so a cloud or a
     * light being switched on does not count as a new scene.
     */
    fun difference(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 1.0
        val meanA = a.average()
        val meanB = b.average()
        var sum = 0.0
        for (i in a.indices) sum += abs((a[i] - meanA) - (b[i] - meanB))
        return (sum / a.size / 128.0).coerceIn(0.0, 1.0)
    }

    /**
     * Whether to look again now. A new view is looked at once at least [minGapMs] has passed since the last look; an unchanged view
     * is looked at again after [forceGapMs] anyway (people walk past, things move). Never before [minGapMs].
     */
    fun shouldLook(changedBy: Double?, sinceLastLookMs: Long, minGapMs: Long, forceGapMs: Long, threshold: Double = 0.10): Boolean =
        when {
            changedBy == null -> true // nothing seen yet
            sinceLastLookMs < minGapMs -> false
            changedBy >= threshold -> true
            else -> sinceLastLookMs >= forceGapMs
        }
}
