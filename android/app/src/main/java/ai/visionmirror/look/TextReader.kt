package ai.visionmirror.look

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Reads printed text in a photo on the phone itself: no internet needed, and the picture never leaves the phone. */
object TextReader {
    /** The text in reading order, or "" when there is none (or reading failed). */
    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(tidy(it.text)) }
            .addOnFailureListener { if (cont.isActive) cont.resume("") }
        cont.invokeOnCancellation { recognizer.close() }
    }

    /** One line per paragraph is hard to listen to; join broken lines into sentences. */
    internal fun tidy(raw: String): String =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(". ").replace("..", ".").trim()
}
