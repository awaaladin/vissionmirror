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

/** What the view models listen to. Each value is what she asked right after the wake phrase ("" if nothing). */
interface WakeSource {
    val wakes: SharedFlow<String>
}

/**
 * Listens for the wake phrase while the app is open and in front, like a voice assistant.
 *
 * It uses the phone's own speech recogniser in short cycles, so (a) it is only on while the app is on screen, never in
 * the background, (b) it stops whenever the assistant is speaking or another part of the app is using the microphone,
 * and (c) it uses the phone's offline speech model when there is one. Some phones play a small sound each time the
 * recogniser restarts; that is the phone's, not ours.
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

    private val foreground = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())

    init {
        scope.launch {
            combine(
                settings.settings.map { it.wakeWord },
                foreground,
                speaker.state.map { it.utteranceId == null },
                voice.busy,
            ) { enabled, front, quiet, busy -> enabled && front && quiet && !busy }
                .distinctUntilChanged()
                .collectLatest { shouldListen -> if (shouldListen) listenLoop() }
        }
    }

    /** Call from the activity's onStart / onStop. */
    fun setForeground(value: Boolean) { foreground.value = value }

    private suspend fun listenLoop() {
        var failures = 0
        while (true) {
            if (!permissions.hasMic() || !SpeechRecognizer.isRecognitionAvailable(context)) {
                delay(3_000)
                continue
            }
            val outcome = withContext(Dispatchers.Main) { listenOnce() }
            when (outcome) {
                is Outcome.Heard -> {
                    failures = 0
                    _wakes.tryEmit(outcome.request)
                    delay(1_500) // let the view model take the microphone before we would listen again
                }
                Outcome.Silence -> { failures = 0; delay(250) }
                Outcome.Busy -> delay(1_000)
                Outcome.Failed -> { failures++; delay((1_000L * failures).coerceAtMost(8_000)) }
            }
        }
    }

    private sealed interface Outcome {
        data class Heard(val request: String) : Outcome
        data object Silence : Outcome
        data object Busy : Outcome
        data object Failed : Outcome
    }

    /** One short listening cycle. Cancelling the coroutine tears the recogniser down. */
    private suspend fun listenOnce(): Outcome = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var finished = false
        fun finish(outcome: Outcome) {
            if (finished) return
            finished = true
            recognizer.setRecognitionListener(null)
            runCatching { recognizer.cancel(); recognizer.destroy() }
            if (cont.isActive) cont.resume(outcome)
        }
        fun check(bundle: Bundle?, final: Boolean) {
            val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            heard.firstNotNullOfOrNull { WakeWord.matches(it) }?.let { finish(Outcome.Heard(it.request)); return }
            if (final) finish(Outcome.Silence)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = check(partialResults, final = false)
            override fun onResults(results: Bundle?) = check(results, final = true)
            override fun onError(error: Int) = finish(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome.Silence
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Outcome.Busy
                    else -> Outcome.Failed
                },
            )
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
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-NG")
            },
        )
    }
}
