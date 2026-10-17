package ai.visionmirror.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

/** The back camera, for pointing at things (text, colours, surroundings). No face analysis. */
@Composable
fun LookCamera(controller: CaptureController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val ratio = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(ratio).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder()
            .setResolutionSelector(ratio)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // speed matters more; the resolution is the same
            .build()
        controller.imageCapture = capture
        provider.unbindAll()
        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
    }

    DisposableEffect(Unit) { onDispose { controller.imageCapture = null } }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}
