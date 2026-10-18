package ai.visionmirror.audio

import ai.visionmirror.assistant.OwnSpeech
import ai.visionmirror.assistant.ResumeWords
import ai.visionmirror.assistant.StopWords
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    data class Match(val request: String, val called: Persona? = null)

    private val special = Regex("\\bspecial\\s+requests?\\b")

    fun matches(utterance: String): Match? {
        val clean = utterance.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        special.find(clean)?.let { return Match(clean.substring(it.range.last + 1).trim()) }

        val words = clean.split(' ')
        for (i in words.indices) {
            // "a donna" is heard as two words as often as "adonna" as one.
            if (isName(words[i])) return Match(requestAfter(words, i, 1), personaOf(words[i]))
            if (i + 1 < words.size && isName(words[i] + words[i + 1])) return Match(requestAfter(words, i, 2), personaOf(words[i] + words[i + 1]))
        }
        return null
    }

    /**
     * What she asked after the name. If the name comes at the start ("Adanna, read this", "hey Adanna, ...") that is
     * everything after it; if it comes later ("switch to Bola") the name is part of the request, so keep it all.
     */
    private fun requestAfter(words: List<String>, nameAt: Int, nameWords: Int): String =
        if (nameAt <= 1) words.drop(nameAt + nameWords).joinToString(" ") else words.joinToString(" ")

    /** [utterance] without the wake phrase, for when the phrase was said in the middle of a request. */
    fun strip(utterance: String): String = matches(utterance)?.request ?: utterance.trim()

    /** Just her name and nothing else ("Adanna", "hey Adanna"): safe to act on even while she is talking. */
    fun isBareName(utterance: String): Boolean {
        val words = utterance.lowercase().replace("'", "").replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ").split(' ')
        val body = if (words.firstOrNull() in setOf("hey", "hi", "ok", "okay")) words.drop(1) else words
        return (body.size == 1 && isName(body[0])) || (body.size == 2 && isName(body[0] + body[1]))
    }

    // "Bola" is short, so only the ways recognisers actually write it count; a loose match would fire on "bowl" or "cola".
    private val bolaForms = setOf("bola", "bolla", "bowla", "bolah", "boller", "bolar")

    private fun personaOf(word: String): Persona = if (word in bolaForms) Persona.Bola else Persona.Adanna

    private fun isName(word: String): Boolean =
        word in bolaForms || (word.length in 4..8 && editDistance(word, TARGET) <= 1)

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

/** She said a wake phrase. [request] is what she asked right after it ("" if nothing); [called] is which name she used. */
data class WakeEvent(val request: String, val called: Persona? = null)

/** What the view models listen to. */
interface WakeSource {
    val wakes: SharedFlow<WakeEvent>
    val hearing: StateFlow<Hearing>

    /** She said "stop" or "pause" (short phrases only). Works while the assistant talks and while the camera scans. */
    val stops: SharedFlow<Unit>

    /** She said "go on" or "continue" while the assistant was quiet. */
    val resumes: SharedFlow<Unit>

    /**
     * While true the wake listener keeps off the microphone. The assistant holds it for its whole turn ("Listening.", then
     * her request), because two recognisers on one microphone make both fail, which sounded like "I can't hear you".
     */
    fun hold(held: Boolean)
}

/**
 * Keeps one speech recogniser running while the app is open and in front, on EVERY screen, like a voice assistant.
 * What counts as a command depends on whether the assistant is talking at that moment:
 * - quiet: her name ("Adanna"), "special request", "stop"/"pause", "go on"/"continue";
 * - talking: only "stop"/"pause" and her name on its own, because the microphone also hears the assistant's own voice.
 *
 * It stays off whenever another part of the app has the microphone, and uses the phone's offline model when it can,
 * falling back to the normal recogniser if the phone refuses those settings. Some phones play a small sound each
 * time the recogniser restarts; that is the phone's, not ours.
 */
@Singleton
class WakeWordListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voice: VoiceInput,
    private val speaker: Speaker,
    private val permissions: PermissionChecker,
    private val own: OwnSpeech,
    settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : WakeSource {

    private val _wakes = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    override val wakes: SharedFlow<WakeEvent> = _wakes.asSharedFlow()

    private val _stops = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    override val stops: SharedFlow<Unit> = _stops.asSharedFlow()

    private val _resumes = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    override val resumes: SharedFlow<Unit> = _resumes.asSharedFlow()

    private val _hearing = MutableStateFlow(Hearing())
    override val hearing: StateFlow<Hearing> = _hearing.asStateFlow()

    private val foreground = MutableStateFlow(false)
    private val held = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wakeOn = true
    @Volatile private var bargeOn = true
    @Volatile private var name = Persona.Adanna

    // Starts strict (offline, Nigerian English) and relaxes if the phone cannot do that.
    private var preferOffline = true
    private var language: String? = "en-NG"

    init {
        scope.launch {
            combine(
                settings.settings.map { Triple(it.wakeWord, it.bargeIn, it.persona) },
                foreground,
                voice.busy,
                held,
            ) { (wake, barge, persona), front, busy, hold ->
                wakeOn = wake
                bargeOn = barge
                name = persona
                (wake || barge) && front && !busy && !hold
            }
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

    override fun hold(held: Boolean) { this.held.value = held }

    /** Call from the activity's onStart / onStop. */
    fun setForeground(value: Boolean) { foreground.value = value }

    private fun talking() = speaker.state.value.utteranceId != null

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
            _hearing.value = Hearing(active = true, status = "Listening for “${name.displayName}”")
            when (val outcome = withContext(Dispatchers.Main) { listenOnce() }) {
                is Outcome.Heard -> {
                    failures = 0
                    _hearing.value = Hearing(active = true, status = "Heard you")
                    _wakes.tryEmit(WakeEvent(outcome.request, outcome.called))
                    delay(1_500) // let the view model take the microphone before we would listen again
                }
                Outcome.Stop -> { failures = 0; _stops.tryEmit(Unit); delay(700) }
                Outcome.Resume -> { failures = 0; _resumes.tryEmit(Unit); delay(700) }
                Outcome.Silence -> { failures = 0; delay(250) }
                // After she finishes talking, wait for the tail of her voice and the room to die away before listening.
                Outcome.Restart -> { failures = 0; delay(if (talking()) 150 else 700) }
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
        data class Heard(val request: String, val called: Persona?) : Outcome
        /** She started or stopped talking mid-cycle: start a clean cycle so her voice and the user's never mix. */
        data object Restart : Outcome
        data object Silence : Outcome
        data object Busy : Outcome
        data object Stop : Outcome
        data object Resume : Outcome
        data class Failed(val code: Int) : Outcome
    }

    /** One listening cycle. Cancelling the coroutine tears the recogniser down. */
    private suspend fun listenOnce(): Outcome = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var finished = false
        var smoothed = 0f
        var watcher: Job? = null
        fun finish(outcome: Outcome) {
            if (finished) return
            finished = true
            watcher?.cancel()
            recognizer.setRecognitionListener(null)
            runCatching { recognizer.cancel(); recognizer.destroy() }
            if (cont.isActive) cont.resume(outcome)
        }
        // A cycle is either "while she talks" or "while she is quiet", never both: her voice must not be heard as the user's.
        val talkingNow = talking()
        fun check(bundle: Bundle?, final: Boolean) {
            // Whatever the microphone hears that is really the assistant's own voice is thrown away.
            val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filterNot { own.isEcho(it) }
            if (heard.isEmpty()) { if (final) finish(Outcome.Silence); return }
            if (bargeOn && heard.any { StopWords.matches(it) }) { finish(Outcome.Stop); return }
            // While she is talking only "stop" counts. She does not listen for names or commands while she speaks.
            if (talkingNow) { if (final) finish(Outcome.Silence); return }
            if (bargeOn && heard.any { ResumeWords.matches(it) }) { finish(Outcome.Resume); return }
            val match = heard.firstNotNullOfOrNull { WakeWord.matches(it) }
            if (match != null && wakeOn) { finish(Outcome.Heard(match.request, match.called)); return }
            heard.firstOrNull()?.let { said -> _hearing.update { it.copy(text = said) } }
            if (final) finish(Outcome.Silence)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                if (talkingNow) return
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
        // The moment she starts or stops talking, end this cycle and begin a clean one.
        watcher = scope.launch(Dispatchers.Main) {
            speaker.state.map { it.utteranceId != null }.first { it != talkingNow }
            finish(Outcome.Restart)
        }
        cont.invokeOnCancellation {
            watcher?.cancel()
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
