package ai.visionmirror.camera

import ai.visionmirror.guidance.FaceObservation
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * On-device ML Kit face detection. Nothing leaves the phone at this stage.
 * Reports the largest face in preview space (upright and mirrored), or null when there is none.
 */
class FaceAnalyzer(
    private val onResult: (FaceObservation?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build(),
    )

    @ExperimentalGetImage
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        // Detection runs on the upright image, so for 90/270 the sensor width and height swap.
        val upright = rotation == 90 || rotation == 270
        val w = (if (upright) proxy.height else proxy.width).toFloat()
        val h = (if (upright) proxy.width else proxy.height).toFloat()

        detector.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { faces ->
                val boxes = faces.map {
                    val b = it.boundingBox
                    FaceObservation.fromBox(
                        b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                        w, h, mirror = true, // front camera only
                    )
                }
                onResult(FaceObservation.largest(boxes))
            }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { proxy.close() }
    }

    fun close() = detector.close()
}
