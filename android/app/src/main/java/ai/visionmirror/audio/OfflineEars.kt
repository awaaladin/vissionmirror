package ai.visionmirror.audio

import ai.visionmirror.di.ApplicationScope
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech recognition that runs entirely on the phone (Vosk, small English model), so the assistant can still hear when the
 * phone's own speech service is missing, old, or cannot reach the internet (its errors 2 and 11). It is the backup for the
 * phone's recogniser, not a replacement: that one is more accurate when it works.
 *
 * The model (about 50 MB) ships inside the app and is copied to the phone's storage the first time, in the background.
 */
@Singleton
class OfflineEars @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    interface Listener {
        /** Words so far in the sentence being spoken. */
        fun onPartial(text: String)

        /** A finished sentence (she paused). May be empty when only noise was heard. */
        fun onSentence(text: String)

        fun onError(message: String)
    }

    /** Stop listening and release the microphone. */
    fun interface Handle { fun stop() }

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile private var model: Model? = null

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        try {
            val dir = File(context.filesDir, "vosk-model")
            val marker = File(dir, ".copied-$MODEL_VERSION")
            if (!marker.exists()) {
                dir.deleteRecursively()
                copyAsset("vosk-model", dir)
                marker.writeText("ok")
            }
            model = Model(dir.absolutePath)
            _ready.value = true
            Log.i("VisionMirror", "offline speech model ready")
        } catch (t: Throwable) {
            Log.e("VisionMirror", "offline speech model could not load", t)
        }
    }

    private fun copyAsset(path: String, to: File) {
        val assets = context.assets
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
        } else {
            to.mkdirs()
            children.forEach { copyAsset("$path/$it", File(to, it)) }
        }
    }

    /**
     * Starts listening. Sentences arrive as she pauses, and listening goes on until [Handle.stop]. [pauseSeconds] is how long
     * a silence ends a sentence. Returns null when the model is not ready (or the microphone cannot be opened).
     */
    fun listen(listener: Listener, pauseSeconds: Float = 1.2f): Handle? {
        val m = model ?: return null
        return try {
            val recognizer = Recognizer(m, SAMPLE_RATE)
            recognizer.setEndpointerDelays(60f, pauseSeconds, 30f)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            val started = service.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    text(hypothesis, "partial").takeIf { it.isNotBlank() }?.let(listener::onPartial)
                }
                override fun onResult(hypothesis: String?) = listener.onSentence(text(hypothesis, "text"))
                override fun onFinalResult(hypothesis: String?) {
                    text(hypothesis, "text").takeIf { it.isNotBlank() }?.let(listener::onSentence)
                }
                override fun onError(exception: Exception?) = listener.onError(exception?.message ?: "offline speech error")
                override fun onTimeout() = Unit
            })
            if (!started) {
                runCatching { service.shutdown(); recognizer.close() }
                return null
            }
            Handle {
                runCatching { service.stop() }
                runCatching { service.shutdown() }
                runCatching { recognizer.close() }
            }
        } catch (t: Throwable) {
            Log.e("VisionMirror", "offline listening could not start", t)
            null
        }
    }

    /**
     * Listens ONLY for a few words ("stop", "pause"...), which is how she can be stopped while she is talking. The microphone
     * is opened the way phone calls open it, with echo cancellation and noise suppression where the phone has them, so
     * the assistant's own voice is largely removed; and because only these words are allowed, nothing else is "heard".
     * [onWords] gets the words heard, as soon as they are heard. Returns null when the engine or microphone is not available.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun listenForWords(words: List<String>, onWords: (String) -> Unit): Handle? {
        val m = model ?: return null
        return try {
            val grammar = words.plus("[unk]").joinToString(",", "[", "]") { "\"$it\"" }
            val recognizer = Recognizer(m, SAMPLE_RATE, grammar)
            val minBuffer = android.media.AudioRecord.getMinBufferSize(16_000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) { recognizer.close(); return null }
            fun open(source: Int) = android.media.AudioRecord(
                source, 16_000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 16_000),
            )
            var record = open(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                record.release()
                record = open(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }
            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) { record.release(); recognizer.close(); return null }
            runCatching { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true }
            runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)?.enabled = true }
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val thread = Thread {
                try {
                    record.startRecording()
                    val buffer = ShortArray(1_600) // 100 ms
                    while (running.get()) {
                        val n = record.read(buffer, 0, buffer.size)
                        if (n <= 0) break
                        val finished = recognizer.acceptWaveForm(buffer, n)
                        val heard = (if (finished) text(recognizer.result, "text") else text(recognizer.partialResult, "partial"))
                            .split(' ').filter { it.isNotBlank() && it != "[unk]" }.joinToString(" ")
                        if (heard.isNotBlank()) {
                            onWords(heard)
                            recognizer.reset() // start fresh, so the same word is not reported twice
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { record.stop() }
                    record.release()
                    runCatching { recognizer.close() }
                }
            }.apply { isDaemon = true; start() }
            Handle {
                running.set(false)
                runCatching { thread.join(800) }
            }
        } catch (t: Throwable) {
            Log.e("VisionMirror", "listening for words could not start", t)
            null
        }
    }

    private fun text(json: String?, key: String): String =
        runCatching { JSONObject(json.orEmpty()).optString(key, "") }.getOrDefault("").trim()

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val MODEL_VERSION = "en-in-0.4"
    }
}
