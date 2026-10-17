package ai.visionmirror.audio

import ai.visionmirror.data.net.PermissionChecker
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.di.ApplicationScope
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The assistant's name and the phrases that wake it: "Adanna" (and how speech recognisers tend to mishear it) or
 * "special request". Matching is deliberately forgiving about the name and strict about everything else, because a
 * false wake is more annoying than asking twice.
 */
object WakeWord {
    const val NAME = "Adanna"
    private const val TARGET = "adanna"

    /** [request] is whatever followed the wake phrase in the same breath ("" when nothing did). */
    data class Match(val request: String)

    private val special = Regex("\\bspecial\\s+requests?\\b")

    fun matches(utterance: String): Match? {
        val clean = utterance.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        special.find(clean)?.let { return Match(clean.substring(it.range.last + 1).trim()) }

        val words = clean.split(' ')
        for (i in words.indices) {
            // "a donna" is heard as two words as often as "adonna" as one.
            if (isName(words[i])) return Match(words.drop(i + 1).joinToString(" "))
            if (i + 1 < words.size && isName(words[i] + words[i + 1])) return Match(words.drop(i + 2).joinToString(" "))
        }
        return null
    }

    /** [utterance] without the wake phrase, for when the phrase was said in the middle of a request. */
    fun strip(utterance: String): String = matches(utterance)?.request ?: utterance.trim()

    private fun isName(word: String): Boolean = word.length in 4..8 && editDistance(word, TARGET) <= 1

    internal fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** What the screen shows while the app waits for her name: proof that it hears her, and what it heard. */
data class Hearing(
    /** The recogniser is running and the app is waiting for the wake phrase. */
    val active: Boolean = false,
    /** Live words as she speaks. Cleared when she stops. */
    val text: String = "",
    /** 0..1 loudness of her voice, for the orb. */
    val level: Float = 0f,
    /** A plain sentence about the state ("Listening for Adanna", or what is wrong). */
    val status: String = "",
)

/** What the view models listen to. Each value is what she asked right after the wake phrase ("" if nothing). */
interface WakeSource {
    val wakes: SharedFlow<String>
    val hearing: StateFlow<Hearing>
}

/**
 * Listens for the wake phrase while the app is open and in front, like a voice assistant.
 *
 * It uses the phone's own speech recogniser in short cycles, so (a) it is only on while the app is on screen, never in
 * the background, (b) it stops whenever the assistant is speaking or another part of the app is using the microphone,
 * and (c) it prefers the phone's offline speech model. If the phone has no offline model for Nigerian English, or the
 * recogniser refuses the language, it falls back to the phone's normal online recogniser instead of failing silently.
 * Some phones play a small sound each time the recogniser restarts; that is the phone's, not ours.
 */
@Singleton
class WakeWordListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voice: VoiceInput,
    private val speaker: Speaker,
    private val permissions: PermissionChecker,
    settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : WakeSource {

    private val _wakes = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val wakes: SharedFlow<String> = _wakes.asSharedFlow()

    private val _hearing = MutableStateFlow(Hearing())
    override val hearing: StateFlow<Hearing> = _hearing.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())

    // Starts strict (offline, Nigerian English) and relaxes if the phone cannot do that.
    private var preferOffline = true
    private var language: String? = "en-NG"

    init {
        scope.launch {
            combine(
                settings.settings.map { it.wakeWord },
                foreground,
                speaker.state.map { it.utteranceId == null },
                voice.busy,
            ) { enabled, front, quiet, busy -> enabled && front && quiet && !busy }
                .distinctUntilChanged()
                .collectLatest { shouldListen ->
                    if (shouldListen) {
                        try { listenLoop() } finally { _hearing.value = Hearing() }
                    } else {
                        _hearing.value = Hearing()
                    }
                }
        }
    }

    /** Call from the activity's onStart / onStop. */
    fun setForeground(value: Boolean) { foreground.value = value }

    private suspend fun listenLoop() {
        var failures = 0
        while (true) {
            if (!permissions.hasMic()) {
                _hearing.value = Hearing(status = "I need the microphone to hear my name. Allow it in settings.")
                delay(3_000)
                continue
            }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _hearing.value = Hearing(status = "This phone has no speech recogniser. Install Speech Services by Google.")
                delay(5_000)
                continue
            }
            _hearing.value = Hearing(active = true, status = "Listening for “${WakeWord.NAME}”")
            when (val outcome = withContext(Dispatchers.Main) { listenOnce() }) {
                is Outcome.Heard -> {
                    failures = 0
                    _hearing.value = Hearing(active = true, status = "Heard you")
                    _wakes.tryEmit(outcome.request)
                    delay(1_500) // let the view model take the microphone before we would listen again
                }
                Outcome.Silence -> { failures = 0; delay(150) }
                Outcome.Busy -> delay(800)
                is Outcome.Failed -> {
                    failures++
                    // These mean "not with these settings": ask the normal recogniser, in the phone's own language.
                    if (outcome.code in RELAX_CODES && (preferOffline || language != null)) {
                        preferOffline = false
                        language = null
                        delay(200)
                    } else {
                        _hearing.value = Hearing(status = "The speech service had a problem (code ${outcome.code}). Trying again.")
                        delay((800L * failures).coerceAtMost(6_000))
                    }
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Heard(val request: String) : Outcome
        data object Silence : Outcome
        data object Busy : Outcome
        data class Failed(val code: Int) : Outcome
    }

    /** One listening cycle. Cancelling the coroutine tears the recogniser down. */
    private suspend fun listenOnce(): Outcome = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var finished = false
        var smoothed = 0f
        fun finish(outcome: Outcome) {
            if (finished) return
            finished = true
            recognizer.setRecognitionListener(null)
            runCatching { recognizer.cancel(); recognizer.destroy() }
            if (cont.isActive) cont.resume(outcome)
        }
        fun check(bundle: Bundle?, final: Boolean) {
            val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            heard.firstOrNull()?.let { said -> _hearing.update { it.copy(text = said) } }
            heard.firstNotNullOfOrNull { WakeWord.matches(it) }?.let { finish(Outcome.Heard(it.request)); return }
            if (final) finish(Outcome.Silence)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val raw = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                smoothed += (raw - smoothed) * 0.4f
                _hearing.update { it.copy(level = smoothed) }
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { _hearing.update { it.copy(level = 0f) } }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = check(partialResults, final = false)
            override fun onResults(results: Bundle?) = check(results, final = true)
            override fun onError(error: Int) {
                _hearing.update { it.copy(text = "", level = 0f) }
                finish(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome.Silence
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Outcome.Busy
                        else -> Outcome.Failed(error)
                    },
                )
            }
        })
        cont.invokeOnCancellation {
            main.post {
                if (!finished) {
                    finished = true
                    recognizer.setRecognitionListener(null)
                    runCatching { recognizer.cancel(); recognizer.destroy() }
                }
            }
        }
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            },
        )
    }

    private companion object {
        // Android's SpeechRecognizer error codes that mean "not with these settings": network timeout (1), network (2),
        // server (4), too many requests (10), server disconnected (11), language not supported (12) or unavailable (13).
        val RELAX_CODES = setOf(1, 2, 4, 10, 11, 12, 13)
    }
}
