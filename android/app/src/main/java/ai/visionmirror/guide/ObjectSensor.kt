package ai.visionmirror.guide

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable

/**
 * Finds everyday objects in a camera picture on the phone itself (about eighty kinds: people, vehicles, furniture,
 * animals...). No internet, and the picture never leaves the phone. The model is EfficientDet-Lite0 from Google's
 * MediaPipe collection, bundled in the app's assets.
 */
class ObjectSensor(context: Context) : Closeable {
    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(8)
            .setScoreThreshold(0.45f)
            .build(),
    )

    fun detect(picture: Bitmap): List<Detected> {
        val w = picture.width.toFloat()
        val h = picture.height.toFloat()
        val result = detector.detect(BitmapImageBuilder(picture).build())
        return result.detections().mapNotNull { d ->
            val top = d.categories().firstOrNull() ?: return@mapNotNull null
            val box = d.boundingBox()
            Detected(
                label = top.categoryName(),
                left = (box.left / w).coerceIn(0f, 1f), top = (box.top / h).coerceIn(0f, 1f),
                right = (box.right / w).coerceIn(0f, 1f), bottom = (box.bottom / h).coerceIn(0f, 1f),
                score = top.score(),
            )
        }
    }

    override fun close() = detector.close()

    private companion object { const val MODEL = "efficientdet_lite0.tflite" }
}
