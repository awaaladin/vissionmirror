package ai.visionmirror.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import java.util.concurrent.Executors

/**
 * The back camera filling the screen, handing the app a small upright picture a few times a second ([minGapMs] apart).
 * [onFrame] runs on a background thread, so it may do slow work; frames that arrive while it is busy are dropped.
 */
@Composable
fun GuideCamera(onFrame: (Bitmap) -> Unit, modifier: Modifier = Modifier, minGapMs: Long = 300L) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFrame = rememberUpdatedState(onFrame)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        var last = 0L
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ratio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { a ->
                a.setAnalyzer(executor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - last >= minGapMs) {
                            last = now
                            val raw = image.toBitmap()
                            val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                            val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            currentOnFrame.value(upright)
                        }
                    } finally {
                        image.close()
                    }
                }
            }
        provider.unbindAll()
        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
