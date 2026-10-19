package ai.visionmirror.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceFailure { NoSpeech, Busy, NoPermission, Network, Unavailable, Other }

sealed interface VoiceEvent {
    /** Microphone is open: play the "listening on" earcon now. */
    data object Ready : VoiceEvent

    /** The user has begun to speak: the app should stop talking and give them its full attention. */
    data object SpeechStarted : VoiceEvent

    /** 0..1, smoothed from the recogniser's RMS. Drives the Quartz ripples. */
    data class Level(val level: Float) : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Failed(val reason: VoiceFailure, val code: Int = 0) : VoiceEvent
}

/** One-shot speech recognition. All calls are made from the main thread. */
interface VoiceInput {
    val events: SharedFlow<VoiceEvent>
    val isAvailable: Boolean

    /** True from start until the recogniser has finished, failed or been cancelled. */
    val busy: StateFlow<Boolean>
    fun start(language: String? = null)

    /** Stop listening and deliver what was heard so far (hold-to-talk release). */
    fun finish()
    fun cancel()
}

@Singleton
class SystemVoiceInput @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offlineEars: OfflineEars,
    private val cloudEars: CloudEars,
    @ai.visionmirror.di.ApplicationScope private val scope: kotlinx.coroutines.CoroutineScope,
) : VoiceInput {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var smoothed = 0f
    private var lastPartial = ""
    private var busyRetries = 0
    private var flips = 0
    private var offlineHandle: OfflineEars.Handle? = null
    private var cloudJob: kotlinx.coroutines.Job? = null
    private var cloudDownUntil = 0L
    private var offlineTimeout: Runnable? = null
    private var lastLanguage: String? = null

    /** Learned: this phone cannot do offline or Nigerian-English recognition, so use its normal recogniser from now on. */
    private var relaxed = false
    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(language: String?) = onMain {
        busyRetries = 0
        flips = 0
        startNow(language)
    }

    private fun startNow(language: String?) {
        lastLanguage = language
        lastPartial = ""
        cancelNow()
        if (startCloud(language)) return
        if (!isAvailable) {
            if (!startOffline()) _events.tryEmit(VoiceEvent.Failed(VoiceFailure.Unavailable))
            return
        }
        smoothed = 0f
        _busy.value = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
            r.setRecognitionListener(listener)
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Use the phone's downloaded speech model when there is one: no network needed, and
                    // it answers faster. Falls back to online recognition when no model is installed.
                    if (!relaxed) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    // Wait for a real pause before deciding she has finished (the default is under a second).
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2400L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
                    if (language != null && !relaxed) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                },
            )
        }
    }

    override fun finish() = onMain {
        if (cloudJob?.isActive == true) { cloudEars.finishNow(); return@onMain }
        if (offlineHandle != null) finishOffline(heardSoFar = true) else recognizer?.stopListening()
        Unit
    }

    /**
     * Hausa, Yoruba and Igbo: no phone recogniser understands them, so the speech is recorded and recognised by VoiceVerse
     * through the backend. If that cannot be done (no internet, service down) she is heard by the phone's own recogniser instead.
     */
    private fun startCloud(language: String?): Boolean {
        val lang = language?.substringBefore('-')?.lowercase()
        if (lang !in CLOUD_LANGUAGES || System.currentTimeMillis() < cloudDownUntil) return false
        _busy.value = true
        smoothed = 0f
        cloudJob = scope.launch {
            val heard = cloudEars.listen(
                lang!!,
                onReady = { main.post { _events.tryEmit(VoiceEvent.Ready) } },
                onLevel = { level -> main.post { _events.tryEmit(VoiceEvent.Level(level)) } },
                onSpeechStart = { main.post { _events.tryEmit(VoiceEvent.SpeechStarted) } },
            )
            main.post {
                if (cloudJob == null) return@post // cancelled meanwhile
                cloudJob = null
                when (heard) {
                    is CloudHeard.Words -> { _busy.value = false; _events.tryEmit(VoiceEvent.Final(heard.english)) }
                    CloudHeard.Nothing -> { _busy.value = false; _events.tryEmit(VoiceEvent.Failed(VoiceFailure.NoSpeech)) }
                    CloudHeard.Failed -> {
                        cloudDownUntil = System.currentTimeMillis() + CLOUD_COOLDOWN_MS
                        startNow(lastLanguage)
                    }
                }
            }
        }
        return true
    }

    /**
     * The phone's recogniser is not working (no service, or no internet): listen with the engine inside the app instead.
     * Returns false when that engine is not ready either.
     */
    private fun startOffline(): Boolean {
        if (offlineHandle != null) return true
        if (!offlineEars.ready.value) return false
        _busy.value = true
        lastPartial = ""
        val handle = offlineEars.listen(
            object : OfflineEars.Listener {
                override fun onPartial(text: String) = onMain {
                    if (offlineHandle == null) return@onMain
                    lastPartial = text
                    _events.tryEmit(VoiceEvent.Level(0.5f))
                    _events.tryEmit(VoiceEvent.Partial(text))
                }
                override fun onSentence(text: String) = onMain {
                    if (offlineHandle == null || text.isBlank() || isNoise(text)) return@onMain
                    lastPartial = text
                    finishOffline(heardSoFar = true)
                }
                override fun onError(message: String) = onMain {
                    if (offlineHandle != null) { stopOffline(); _events.tryEmit(VoiceEvent.Failed(VoiceFailure.Other)) }
                }
            },
            pauseSeconds = 1.6f,
        )
        if (handle == null) { _busy.value = false; return false }
        offlineHandle = handle
        _events.tryEmit(VoiceEvent.Ready)
        // Nothing said for a while: give up politely (the patience window above this will try again).
        offlineTimeout = Runnable { finishOffline(heardSoFar = false) }.also { main.postDelayed(it, OFFLINE_WAIT_MS) }
        return true
    }

    private fun stopOffline() {
        offlineTimeout?.let(main::removeCallbacks)
        offlineTimeout = null
        offlineHandle?.stop()
        offlineHandle = null
        _busy.value = false
    }

    private fun finishOffline(heardSoFar: Boolean) {
        val said = lastPartial
        stopOffline()
        _events.tryEmit(
            if (heardSoFar && said.isNotBlank() && !isNoise(said)) VoiceEvent.Final(said) else VoiceEvent.Failed(VoiceFailure.NoSpeech),
        )
    }

    override fun cancel() = onMain { cancelNow() }

    private fun cancelNow() {
        cloudJob?.cancel()
        cloudJob = null
        stopOffline()
        recognizer?.apply {
            setRecognitionListener(null)
            cancel()
            destroy()
        }
        recognizer = null
        _busy.value = false
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _events.tryEmit(VoiceEvent.Ready) }
        override fun onBeginningOfSpeech() { _events.tryEmit(VoiceEvent.SpeechStarted) }
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
            partialResults.firstResult()?.let { lastPartial = it; _events.tryEmit(VoiceEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            val text = results.firstResult()
            _events.tryEmit(if (text.isNullOrBlank() || isNoise(text)) VoiceEvent.Failed(VoiceFailure.NoSpeech) else VoiceEvent.Final(text))
            recognizer?.destroy()
            recognizer = null
            _busy.value = false
        }

        override fun onError(error: Int) {
            Log.w("VisionMirror", "speech recogniser error $error (relaxed=$relaxed)")
            // "Not with these settings" (no offline model, language not supported or unavailable, network or server
            // trouble): try once more with the phone's normal recogniser and language, and remember that for next time.
            if (error in RELAX_CODES && flips < 2) {
                flips++
                relaxed = !relaxed
                val language = lastLanguage
                recognizer?.apply { setRecognitionListener(null); cancel(); destroy() }
                recognizer = null
                main.post { startNow(language) }
                return
            }
            // The recogniser is sometimes still releasing from the last use. Ask again shortly instead of giving up.
            if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && busyRetries < 3) {
                busyRetries++
                val language = lastLanguage
                recognizer?.apply { setRecognitionListener(null); cancel(); destroy() }
                recognizer = null
                main.postDelayed({ startNow(language) }, 350L * busyRetries)
                return
            }
            // She spoke, but the recogniser gave up before it finished (a common end to a good sentence). Use what it heard.
            val saved = lastPartial
            if (saved.isNotBlank() && !isNoise(saved) && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                _events.tryEmit(VoiceEvent.Final(saved))
                recognizer?.destroy()
                recognizer = null
                _busy.value = false
                return
            }
            // The phone's recogniser cannot work right now (no internet, service trouble): use the one inside the app.
            if (error in RELAX_CODES || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_AUDIO) {
                recognizer?.apply { setRecognitionListener(null); cancel(); destroy() }
                recognizer = null
                if (startOffline()) return
            }
            val reason = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NoSpeech
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.Busy
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.NoPermission
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                -> VoiceFailure.Network
                else -> VoiceFailure.Other
            }
            _events.tryEmit(VoiceEvent.Failed(reason, error))
            recognizer?.destroy()
            recognizer = null
            _busy.value = false
        }
    }

    private companion object {
        // network timeout (1), network (2), server (4), too many requests (10), server disconnected (11),
        // language not supported (12), language unavailable (13)
        val RELAX_CODES = setOf(1, 2, 4, 10, 11, 12, 13)
        const val OFFLINE_WAIT_MS = 7_000L
        val CLOUD_LANGUAGES = setOf("ha", "yo", "ig")
        const val CLOUD_COOLDOWN_MS = 120_000L
        val FILLERS = setOf("uh", "um", "umm", "hmm", "hm", "mm", "mhm", "ah", "oh", "eh", "huh", "er", "erm", "the", "a")
    }

    /** A cough, a click or a stray sound the recogniser wrote down as a word: not something she said to the assistant. */
    private fun isNoise(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^a-z ]"), "").trim()
        return t.length < 2 || t in FILLERS
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
