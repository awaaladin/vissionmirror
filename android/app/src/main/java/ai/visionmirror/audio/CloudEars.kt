package ai.visionmirror.audio

import ai.visionmirror.data.api.MirrorApi
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** Decides, from the loudness of each 50 ms of sound, when she has started speaking and when she has finished. Pure, so it is tested. */
class SpeechGate(private val frameMs: Int = 50, private val patienceMs: Int = 1_500, private val waitForStartMs: Int = 7_000, private val maxMs: Int = 15_000) {
    enum class Decision { Continue, Finished, NothingHeard }

    private var elapsed = 0
    private var calibration = 0.0
    private var calibrationFrames = 0
    private var loudFrames = 0
    private var silentMs = 0
    var started = false
        private set
    private var threshold = 600.0

    fun feed(rms: Double): Decision {
        elapsed += frameMs
        // The first moments are the room's own noise: learn how loud it is, so the threshold fits this room.
        if (calibrationFrames < CALIBRATION_FRAMES) {
            calibration += rms
            calibrationFrames++
            if (calibrationFrames == CALIBRATION_FRAMES) threshold = maxOf(calibration / calibrationFrames * 3.0, MIN_THRESHOLD)
            return Decision.Continue
        }
        if (!started) {
            loudFrames = if (rms > threshold) loudFrames + 1 else 0
            if (loudFrames >= 2) started = true
        } else {
            silentMs = if (rms < threshold * 0.6) silentMs + frameMs else 0
            if (silentMs >= patienceMs) return Decision.Finished
        }
        if (!started && elapsed >= waitForStartMs) return Decision.NothingHeard
        if (elapsed >= maxMs) return if (started) Decision.Finished else Decision.NothingHeard
        return Decision.Continue
    }

    private companion object {
        const val CALIBRATION_FRAMES = 6 // 300 ms
        const val MIN_THRESHOLD = 450.0
    }
}

/** A 16 kHz mono 16-bit WAV file around raw sound. */
fun wavOf(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
    val out = ByteArrayOutputStream(pcm.size + 44)
    fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
    out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
    out.write("data".toByteArray()); int(pcm.size)
    out.write(pcm)
    return out.toByteArray()
}

/** What listening in Hausa, Yoruba or Igbo came to. */
sealed interface CloudHeard {
    /** [text] in her own language, [english] the same in English (so commands and the AI can understand it). */
    data class Words(val text: String, val english: String) : CloudHeard
    data object Nothing : CloudHeard
    /** The recording or the service failed: use another way of listening. */
    data object Failed : CloudHeard
}

/**
 * Hears Hausa, Yoruba and Igbo, which no phone's speech recogniser handles: records a few seconds, stopping when she pauses,
 * and has the backend (VoiceVerse speech recognition) turn it into words. Needs internet; nothing is stored.
 */
@Singleton
class CloudEars @Inject constructor(
    private val api: dagger.Lazy<MirrorApi>,
) {
    @Volatile private var finishNow = false

    /** She released the button: stop recording now and use what there is. */
    fun finishNow() { finishNow = true }

    @SuppressLint("MissingPermission")
    suspend fun listen(language: String, onReady: () -> Unit, onLevel: (Float) -> Unit, onSpeechStart: () -> Unit): CloudHeard = withContext(Dispatchers.IO) {
        finishNow = false
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext CloudHeard.Failed
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 8))
        } catch (e: Exception) {
            return@withContext CloudHeard.Failed
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext CloudHeard.Failed
        }
        val pcm = ByteArrayOutputStream()
        val gate = SpeechGate()
        var announced = false
        var decision = SpeechGate.Decision.Continue
        try {
            recorder.startRecording()
            onReady()
            val frame = ShortArray(FRAME / 2)
            val bytes = ByteArray(FRAME)
            while (currentCoroutineContext().isActive && decision == SpeechGate.Decision.Continue && !finishNow) {
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) return@withContext CloudHeard.Failed
                var sum = 0.0
                for (i in 0 until n) {
                    sum += frame[i].toDouble() * frame[i]
                    bytes[2 * i] = frame[i].toByte(); bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte()
                }
                pcm.write(bytes, 0, n * 2)
                val rms = sqrt(sum / n)
                onLevel((rms / 4_000.0).toFloat().coerceIn(0f, 1f))
                decision = gate.feed(rms)
                if (gate.started && !announced) { announced = true; onSpeechStart() }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!gate.started && decision != SpeechGate.Decision.Finished && !(finishNow && announced)) return@withContext CloudHeard.Nothing
        val audio = wavOf(pcm.toByteArray())
        try {
            val response = api.get().transcribe(language, audio.toRequestBody("audio/wav".toMediaType()))
            val body = response.body()
            when {
                response.isSuccessful && body != null && body.text.isBlank() -> CloudHeard.Nothing
                response.isSuccessful && body != null -> CloudHeard.Words(body.text, body.english.ifBlank { body.text })
                else -> CloudHeard.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudHeard.Failed
        }
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME = 1_600 // 50 ms of 16-bit mono
    }
}
