package ai.visionmirror.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceFailure { NoSpeech, Busy, NoPermission, Network, Unavailable, Other }

sealed interface VoiceEvent {
    /** Microphone is open: play the "listening on" earcon now. */
    data object Ready : VoiceEvent

    /** 0..1, smoothed from the recogniser's RMS. Drives the Quartz ripples. */
    data class Level(val level: Float) : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Failed(val reason: VoiceFailure) : VoiceEvent
}

/** One-shot speech recognition. All calls are made from the main thread. */
interface VoiceInput {
    val events: SharedFlow<VoiceEvent>
    val isAvailable: Boolean
    fun start(language: String? = null)

    /** Stop listening and deliver what was heard so far (hold-to-talk release). */
    fun finish()
    fun cancel()
}

@Singleton
class SystemVoiceInput @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceInput {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var smoothed = 0f

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(language: String?) = onMain {
        cancelNow()
        if (!isAvailable) {
            _events.tryEmit(VoiceEvent.Failed(VoiceFailure.Unavailable))
            return@onMain
        }
        smoothed = 0f
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(listener)
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    if (language != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                },
            )
        }
    }

    override fun finish() = onMain { recognizer?.stopListening() ?: Unit }

    override fun cancel() = onMain { cancelNow() }

    private fun cancelNow() {
        recognizer?.apply {
            setRecognitionListener(null)
            cancel()
            destroy()
        }
        recognizer = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _events.tryEmit(VoiceEvent.Ready) }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            // Typical range is about -2..10 dB. Smooth so the ripples breathe instead of flicker.
            val raw = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            smoothed += (raw - smoothed) * 0.4f
            _events.tryEmit(VoiceEvent.Level(smoothed))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstResult()?.let { _events.tryEmit(VoiceEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult()
            _events.tryEmit(if (text.isNullOrBlank()) VoiceEvent.Failed(VoiceFailure.NoSpeech) else VoiceEvent.Final(text))
            recognizer?.destroy()
            recognizer = null
        }

        override fun onError(error: Int) {
            val reason = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NoSpeech
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.Busy
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.NoPermission
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                -> VoiceFailure.Network
                else -> VoiceFailure.Other
            }
            _events.tryEmit(VoiceEvent.Failed(reason))
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
