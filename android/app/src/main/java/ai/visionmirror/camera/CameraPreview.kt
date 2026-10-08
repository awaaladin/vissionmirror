package ai.visionmirror.camera

import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.guidance.FaceObservation
import ai.visionmirror.imaging.ImageProcessor
import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** A captured, upright, downscaled frame plus its JPEG bytes (EXIF-free). */
class CapturedPhoto(val bitmap: Bitmap, val jpeg: ByteArray)

/** Lets the Mirror view model trigger a capture without touching CameraX. */
class CaptureController {
    internal var imageCapture: ImageCapture? = null

    /** Null if the camera is not ready or the capture failed. */
    suspend fun capture(context: Context): CapturedPhoto? {
        val capture = imageCapture ?: return null
        val executor = ContextCompat.getMainExecutor(context)
        val proxy: ImageProxy = suspendCancellableCoroutine<ImageProxy?> { cont ->
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                override fun onError(exception: ImageCaptureException) { cont.resume(null) }
            })
        } ?: return null

        return try {
            val raw = proxy.toBitmap()
            val upright = ImageProcessor.prepare(raw, proxy.imageInfo.rotationDegrees)
            CapturedPhoto(upright, ImageProcessor.toJpeg(upright))
        } finally {
            proxy.close()
        }
    }
}

/**
 * Front camera preview with on-device face analysis. 4:3 on both preview and analysis so the
 * normalised face box lines up with what is drawn inside the oval frame.
 */
@Composable
fun CameraPreview(
    controller: CaptureController,
    onFace: (FaceObservation?) -> Unit,
    modifier: Modifier = Modifier,
    facing: CameraFacing = CameraFacing.Front,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFace = rememberUpdatedState(onFace)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember(facing) { FaceAnalyzer(mirror = facing == CameraFacing.Front) { currentOnFace.value(it) } }

    LaunchedEffect(lifecycleOwner, analyzer) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ratio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        val capture = ImageCapture.Builder()
            .setResolutionSelector(ratio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        controller.imageCapture = capture
        provider.unbindAll()
        val selector = if (facing == CameraFacing.Front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // A phone with only one camera cannot switch: fall back to whichever it has rather than crash.
        val usable = if (provider.hasCamera(selector)) selector else {
            if (facing == CameraFacing.Front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        }
        provider.bindToLifecycle(lifecycleOwner, usable, preview, analysis, capture)
    }

    DisposableEffect(analyzer) { onDispose { analyzer.close() } }
    DisposableEffect(Unit) {
        onDispose {
            controller.imageCapture = null
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
