package ai.visionmirror.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What view models depend on, so they can be tested without Android's TextToSpeech. */
interface Speaker {
    val state: StateFlow<SpeechManager.State>
    val events: SharedFlow<SpeechManager.Event>
    fun speak(text: String, priority: SpeechManager.Priority, id: String): String
    fun stop()
    fun pause()
    fun resume()
}

/** Convenience with defaults (interfaces can't be overridden with default arguments). */
fun Speaker.say(
    text: String,
    priority: SpeechManager.Priority = SpeechManager.Priority.Queue,
    id: String = UUID.randomUUID().toString(),
): String = speak(text, priority, id)

/**
 * The one and only voice. Everything the app says goes through here so audio never overlaps.
 *
 * - [Priority.Queue] waits its turn; [Priority.Interrupt] flushes everything (errors, user commands).
 * - Pause/resume: Android's TTS has no pause, so we stop and later re-speak from the word that was
 *   being read. Ranges are reported against the *original* text so the highlight stays correct.
 * - Audio attributes use the accessibility usage and we hold transient audio focus (may duck) only
 *   while speaking.
 */
@Singleton
class SpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : Speaker {
    enum class Priority { Queue, Interrupt }

    data class State(
        val isSpeaking: Boolean = false,
        val isPaused: Boolean = false,
        val utteranceId: String? = null,
        /** Full text of the current utterance (what the screen should display). */
        val text: String = "",
        /** Character range of the word being spoken now, in [text]. */
        val wordRange: IntRange? = null,
    )

    sealed interface Event {
        data class Started(val id: String) : Event
        data class Done(val id: String) : Event
        data class Failed(val id: String) : Event
    }

    private class Item(val id: String, val text: String, var base: Int = 0)

    private val _state = MutableStateFlow(State())
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    override val events: SharedFlow<Event> = _events.asSharedFlow()

    private val lock = Any()
    private val queue = ArrayDeque<Item>() // head is the item being spoken
    private var tts: TextToSpeech? = null
    private var ready = false
    private var paused = false
    private var rate = 1.0f
    private var pitch = 1.0f
    private var voiceName: String? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .build()

    init {
        tts = TextToSpeech(context) { status ->
            synchronized(lock) {
                if (status != TextToSpeech.SUCCESS) return@synchronized
                tts?.apply {
                    setAudioAttributes(speechAttributes)
                    language = Locale.getDefault().takeIf { isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
                        ?: Locale.US
                    setSpeechRate(rate)
                    setPitch(pitch)
                    applyVoice()
                    setOnUtteranceProgressListener(listener)
                }
                ready = true
                // Anything requested before the engine woke up.
                if (!paused) queue.forEachIndexed { i, item -> enqueueToEngine(item, i == 0) }
            }
        }
    }

    // ---- public API ------------------------------------------------------------------------

    /** Returns the utterance id so callers can match [Event]s. */
    override fun speak(text: String, priority: Priority, id: String): String {
        if (text.isBlank()) return id
        synchronized(lock) {
            if (priority == Priority.Interrupt) {
                queue.clear()
                paused = false
                if (ready) tts?.stop()
            }
            val item = Item(id, text)
            queue.addLast(item)
            if (queue.size == 1) _state.value = State(text = text, utteranceId = id, isPaused = paused)
            if (ready && !paused) {
                requestFocus()
                enqueueToEngine(item, flush = false)
            }
        }
        return id
    }

    override fun stop() = synchronized(lock) {
        queue.clear()
        paused = false
        if (ready) tts?.stop()
        _state.value = State()
        abandonFocus()
    }

    override fun pause() = synchronized(lock) {
        val head = queue.firstOrNull() ?: return@synchronized
        if (paused) return@synchronized
        head.base = _state.value.wordRange?.first ?: head.base
        paused = true
        tts?.stop()
        _state.value = _state.value.copy(isSpeaking = false, isPaused = true)
    }

    override fun resume() = synchronized(lock) {
        if (!paused) return@synchronized
        paused = false
        requestFocus()
        queue.forEachIndexed { i, item -> enqueueToEngine(item, flush = false) }
        _state.value = _state.value.copy(isPaused = false)
    }

    fun setRate(value: Float) = synchronized(lock) { rate = value; tts?.setSpeechRate(value); Unit }

    fun setPitch(value: Float) = synchronized(lock) { pitch = value; tts?.setPitch(value); Unit }

    /** Voices matching the current language, for the Settings picker. */
    fun availableVoices(): List<Voice> = synchronized(lock) {
        val lang = tts?.language?.language
        runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { !it.isNetworkConnectionRequired && it.locale.language == lang }
            .sortedBy { it.name }
    }

    fun setVoice(name: String?) = synchronized(lock) { voiceName = name; tts?.applyVoice(); Unit }

    fun shutdown() = synchronized(lock) {
        tts?.shutdown()
        tts = null
        ready = false
    }

    // ---- internals -------------------------------------------------------------------------

    private fun TextToSpeech.applyVoice() {
        val wanted = voiceName ?: return
        runCatching { voices.firstOrNull { it.name == wanted } }.getOrNull()?.let { voice = it }
    }

    private fun enqueueToEngine(item: Item, flush: Boolean) {
        val spoken = item.text.substring(item.base.coerceIn(0, item.text.length))
        tts?.speak(spoken, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), item.id)
    }

    private fun requestFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            synchronized(lock) {
                val item = queue.firstOrNull { it.id == utteranceId } ?: return
                _state.value = State(
                    isSpeaking = true,
                    utteranceId = utteranceId,
                    text = item.text,
                    wordRange = null,
                )
            }
            _events.tryEmit(Event.Started(utteranceId))
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            synchronized(lock) {
                val item = queue.firstOrNull { it.id == utteranceId } ?: return
                if (paused) return
                // The engine counts from the start of what we handed it; shift back to original text.
                _state.value = _state.value.copy(wordRange = (start + item.base) until (end + item.base))
            }
        }

        override fun onDone(utteranceId: String) {
            val finished = synchronized(lock) {
                if (paused || queue.firstOrNull()?.id != utteranceId) return
                queue.removeFirst()
                if (queue.isEmpty()) {
                    _state.value = State()
                    abandonFocus()
                }
                true
            }
            if (finished) _events.tryEmit(Event.Done(utteranceId))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            synchronized(lock) {
                if (paused) return
                queue.removeAll { it.id == utteranceId }
                if (queue.isEmpty()) {
                    _state.value = State()
                    abandonFocus()
                }
            }
            _events.tryEmit(Event.Failed(utteranceId))
        }

        // Stops are always initiated by us (stop / pause / interrupt) and handled there.
        override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
    }
}
