package ai.visionmirror.imaging

import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a photo for upload: rotate upright, scale to about 1280 px on the long side, JPEG at 85.
 * Re-encoding from a Bitmap writes a brand-new JPEG, so EXIF (including GPS location) is not carried over.
 */
object ImageProcessor {
    const val MAX_LONG_SIDE = 1280
    const val JPEG_QUALITY = 85

    /** Pure sizing maths, unit-tested. Never upscales. */
    fun targetSize(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        val longSide = max(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toFloat() / longSide
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Rotates by [rotationDegrees] (from CameraX) and scales down. Returns a new, upright bitmap. */
    fun prepare(source: Bitmap, rotationDegrees: Int): Bitmap {
        val rotated = if (rotationDegrees % 360 != 0) {
            Bitmap.createBitmap(
                source, 0, 0, source.width, source.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
            )
        } else {
            source
        }
        val (w, h) = targetSize(rotated.width, rotated.height)
        return if (w == rotated.width && h == rotated.height) rotated else Bitmap.createScaledBitmap(rotated, w, h, true)
    }

    fun toJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
