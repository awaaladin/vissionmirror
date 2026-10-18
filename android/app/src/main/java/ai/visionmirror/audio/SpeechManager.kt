package ai.visionmirror.audio

import ai.visionmirror.assistant.OwnSpeech
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What view models depend on, so they can be tested without Android's TextToSpeech. */
interface Speaker {
    val state: StateFlow<SpeechManager.State>
    val events: SharedFlow<SpeechManager.Event>
    /** [local] = urgent: a short acknowledgement like "Listening." that must start at once, in the same voice as everything else. */
    fun speak(text: String, priority: SpeechManager.Priority, id: String, local: Boolean = false): String
    fun stop()
    fun pause()
    fun resume()
}

/** Convenience with defaults (interfaces can't be overridden with default arguments). */
fun Speaker.say(
    text: String,
    priority: SpeechManager.Priority = SpeechManager.Priority.Queue,
    id: String = UUID.randomUUID().toString(),
    local: Boolean = false,
): String = speak(text, priority, id, local)

/**
 * The one and only voice. Everything the app says goes through here so audio never overlaps.
 *
 * - [Priority.Queue] waits its turn; [Priority.Interrupt] flushes everything (errors, user commands).
 * - One utterance plays at a time. Each is spoken by the cloud Nigerian voice ([CloudVoice]) when that is on and
 *   reachable, and by the phone's text-to-speech otherwise, so she is never left in silence.
 * - Pause/resume: Android's TTS has no pause, so we stop and later re-speak from the word that was
 *   being read (ranges are reported against the *original* text so the highlight stays correct).
 *   Cloud audio pauses and resumes exactly; it has no word highlight.
 * - We hold transient audio focus (may duck) only while speaking.
 */
@Singleton
class SpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloud: CloudVoice,
    private val own: OwnSpeech,
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

    private enum class Mode { Waiting, Fetching, Cloud, Local }

    private companion object {
        const val URGENT_TIMEOUT_MS = 1_500L
        const val CACHE_MAX_CHARS = 160
    }

    private class Item(val id: String, val text: String) {
        var base = 0
        var local = false
        var mode = Mode.Waiting
        var fetch: Job? = null
        var player: MediaPlayer? = null
        var prepared = false
        var file: File? = null

        fun release() {
            fetch?.cancel()
            fetch = null
            runCatching { player?.release() }
            player = null
            prepared = false
            file?.delete()
            file = null
            mode = Mode.Waiting
        }
    }

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
    private var persona = Persona.Adanna
    private var voiceName: String? = null
    private var cloudEnabled = true
    private var locales: List<Locale> = listOf(Locale("en", "NG"), Locale.getDefault(), Locale.US)

    private val main = Handler(Looper.getMainLooper())

    // Short sentences already spoken (or prefetched) in the active voice, so repeats start instantly.
    private val phraseCache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 60
    }

    /** The language of what is being said ("ha", "yo", "ig", "en"). Lock held. */
    private fun spokenLanguage() = locales.firstOrNull()?.language ?: "en"
    private fun cacheKey(text: String, speed: Float) = "${persona.name}|${spokenLanguage()}|${(speed * 20).toInt()}|$text"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // The normal media volume (the one the phone's volume keys change). The separate "accessibility" volume
    // is often muted or unreachable on phones without a screen reader running, which made the voice silent.
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .build()

    /** Null while starting or when speech works; otherwise why the phone cannot speak (shown in Developer tools). */
    @Volatile var problem: String? = null
        private set

    init {
        tts = TextToSpeech(context) { status ->
            synchronized(lock) {
                if (status != TextToSpeech.SUCCESS) {
                    problem = "The phone's text-to-speech engine did not start (code $status). " +
                        "Install or enable \"Speech Services by Google\" in Settings > Text-to-speech output."
                    Log.e("VisionMirror", problem!!)
                    return@synchronized
                }
                tts?.apply {
                    setAudioAttributes(speechAttributes)
                    pickLanguage()
                    setSpeechRate(rate)
                    setPitch(effectivePitch())
                    setOnUtteranceProgressListener(listener)
                }
                ready = true
                // Anything requested before the engine woke up.
                pump()
            }
        }
    }

    // ---- public API ------------------------------------------------------------------------

    /** Returns the utterance id so callers can match [Event]s. */
    override fun speak(text: String, priority: Priority, id: String, local: Boolean): String {
        if (text.isBlank()) return id
        synchronized(lock) {
            if (priority == Priority.Interrupt) {
                queue.forEach { it.release() }
                queue.clear()
                paused = false
                if (ready) tts?.stop()
            }
            own.remember(text)
            val item = Item(id, text).also { it.local = local }
            queue.addLast(item)
            if (queue.size == 1) _state.value = State(text = text, utteranceId = id, isPaused = paused)
            pump()
        }
        return id
    }

    override fun stop() = synchronized(lock) {
        queue.forEach { it.release() }
        queue.clear()
        paused = false
        if (ready) tts?.stop()
        _state.value = State()
        abandonFocus()
    }

    override fun pause() = synchronized(lock) {
        val head = queue.firstOrNull() ?: return@synchronized
        if (paused) return@synchronized
        paused = true
        when (head.mode) {
            Mode.Cloud -> runCatching { head.player?.pause() }
            Mode.Fetching -> head.release() // nothing is playing yet; it starts again on resume
            Mode.Local -> {
                head.base = _state.value.wordRange?.first ?: head.base
                head.mode = Mode.Waiting
                tts?.stop()
            }
            Mode.Waiting -> Unit
        }
        _state.value = _state.value.copy(isSpeaking = false, isPaused = true)
    }

    override fun resume() = synchronized(lock) {
        if (!paused) return@synchronized
        paused = false
        val head = queue.firstOrNull()
        if (head?.mode == Mode.Cloud) {
            requestFocus()
            if (head.prepared) runCatching { head.player?.start() }
            _state.value = _state.value.copy(isSpeaking = true, isPaused = false)
        } else {
            _state.value = _state.value.copy(isPaused = false)
            pump()
        }
    }

    fun setRate(value: Float) = synchronized(lock) { rate = value; tts?.setSpeechRate(value); Unit }

    /**
     * Speak with the first of [wanted] this phone has a voice for. Nigerian English is first, so the default is the
     * phone's Nigerian voice (its rhythm and vowels), not an American or British one.
     */
    fun setLanguage(wanted: List<Locale>) = synchronized(lock) {
        locales = wanted
        if (ready) tts?.pickLanguage()
        Unit
    }

    /** The natural Nigerian voice from the internet. Off = always the phone's own voice. */
    fun setCloudVoice(enabled: Boolean) = synchronized(lock) { cloudEnabled = enabled; Unit }

    /** Fetch [phrases] in the active voice now, so they are instant later. Safe to call often; known phrases are skipped. */
    fun prefetch(phrases: List<String>) {
        val (male, speed) = synchronized(lock) { if (!cloudEnabled) return; persona.male to rate }
        val language = synchronized(lock) { spokenLanguage() }
        scope.launch {
            for (p in phrases) {
                val key = synchronized(lock) { cacheKey(p, speed) }
                if (synchronized(lock) { phraseCache.containsKey(key) }) continue
                val bytes = cloud.synthesize(p, speed, male, null, language) ?: return@launch // offline: stop, try again next time
                synchronized(lock) { phraseCache[key] = bytes }
            }
        }
    }

    fun setPitch(value: Float) = synchronized(lock) { pitch = value; tts?.setPitch(effectivePitch()); Unit }

    /** Adanna or Bola: the cloud voice follows it, and the phone's own voice gets a deeper pitch for Bola. */
    fun setPersona(value: Persona) = synchronized(lock) {
        persona = value
        tts?.setPitch(effectivePitch())
        tts?.applyVoice()
        Unit
    }

    // The phone's voices do not say whether they are a man's or a woman's, so Bola is a lower-pitched voice (and a different
    // installed voice when there is one), not a guaranteed man's voice. The cloud voice is a real man's voice.
    private fun effectivePitch(): Float = if (persona.male) pitch * 0.78f else pitch

    /** Voices matching the current language, for the Settings picker. */
    fun availableVoices(): List<Voice> = synchronized(lock) {
        val lang = tts?.language?.language
        runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { !it.isNetworkConnectionRequired && it.locale.language == lang }
            .sortedBy { it.name }
    }

    fun setVoice(name: String?) = synchronized(lock) { voiceName = name; tts?.applyVoice(); Unit }

    fun shutdown() = synchronized(lock) {
        queue.forEach { it.release() }
        queue.clear()
        scope.cancel()
        tts?.shutdown()
        tts = null
        ready = false
    }

    // ---- internals -------------------------------------------------------------------------

    private fun TextToSpeech.pickLanguage() {
        val picked = locales.firstOrNull { isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
        val result = setLanguage(picked)
        problem = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            "The phone has no voice data for ${picked.displayLanguage}. " +
                "Download one in Settings > Text-to-speech output."
        } else {
            null
        }
        problem?.let { Log.e("VisionMirror", it) }
        applyVoice()
    }

    /** The voice she chose if it fits the language; otherwise the best offline voice for the language's country. */
    private fun TextToSpeech.applyVoice() {
        val all = runCatching { voices.orEmpty() }.getOrDefault(emptySet())
        val current = language ?: return
        val chosen = voiceName?.let { name -> all.firstOrNull { it.name == name && it.locale.language == current.language } }
        val candidates = all
            .filter { it.locale.language == current.language && it.locale.country == current.country && !it.isNetworkConnectionRequired }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name })
        // Bola takes a different installed voice from Adanna when the phone has more than one.
        val best = chosen ?: (if (persona.male) candidates.getOrNull(1) ?: candidates.firstOrNull() else candidates.firstOrNull())
        best?.let { voice = it }
    }

    /** Start the first utterance if nothing is playing. Called with [lock] held. */
    private fun pump() {
        if (!ready || paused) return
        val head = queue.firstOrNull() ?: return
        if (head.mode != Mode.Waiting) return
        requestFocus()
        // The same voice for everything: Adanna is Adanna and Bola is Bola in every sentence, short or long. The phone's own
        // voice is only the fallback when the cloud voice is off or unreachable.
        if (cloudEnabled) startCloud(head) else startLocal(head)
    }

    private fun startLocal(item: Item) {
        item.mode = Mode.Local
        val spoken = item.text.substring(item.base.coerceIn(0, item.text.length))
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, Bundle(), item.id)
    }

    private fun startCloud(item: Item) {
        item.mode = Mode.Fetching
        val spoken = item.text.substring(item.base.coerceIn(0, item.text.length))
        val speed = rate
        val language = spokenLanguage()
        item.fetch = scope.launch {
            val key = cacheKey(spoken, speed)
            val cached = synchronized(lock) { phraseCache[key] }
            // Urgent phrases give the cloud only a moment; if it is slow the phone speaks instead of leaving her waiting.
            val bytes = cached ?: cloud.synthesize(spoken, speed, persona.male, if (item.local) URGENT_TIMEOUT_MS else null, language)
                ?.also { if (spoken.length <= CACHE_MAX_CHARS) synchronized(lock) { phraseCache[key] = it } }
            val file = bytes?.let { runCatching { File.createTempFile("voice", ".mp3", context.cacheDir).apply { writeBytes(it) } }.getOrNull() }
            main.post { synchronized(lock) { onFetched(item, file) } }
        }
    }

    /** Main thread, [lock] held. The audio is ready (or not): play it, or fall back to the phone's voice. */
    private fun onFetched(item: Item, file: File?) {
        if (item.mode != Mode.Fetching || queue.firstOrNull() !== item) {
            file?.delete() // interrupted or stopped while it was downloading
            return
        }
        if (file == null) {
            startLocal(item)
            return
        }
        item.file = file
        try {
            val player = MediaPlayer()
            item.player = player
            item.mode = Mode.Cloud
            player.setAudioAttributes(speechAttributes)
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                synchronized(lock) {
                    if (item.player !== player) return@synchronized
                    item.prepared = true
                    if (!paused) player.start()
                    _state.value = State(isSpeaking = !paused, isPaused = paused, utteranceId = item.id, text = item.text)
                }
                _events.tryEmit(Event.Started(item.id))
            }
            player.setOnCompletionListener { finish(item.id) }
            player.setOnErrorListener { _, _, _ ->
                synchronized(lock) {
                    if (queue.firstOrNull() === item && item.mode == Mode.Cloud) {
                        item.release()
                        startLocal(item)
                    }
                }
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w("VisionMirror", "cloud voice could not play: ${e.message}")
            item.release()
            startLocal(item)
        }
    }

    /** The head utterance has been fully spoken, by either voice. */
    private fun finish(id: String) {
        val done = synchronized(lock) {
            val head = queue.firstOrNull()
            if (paused || head?.id != id) return@synchronized false
            queue.removeFirst().release()
            if (queue.isEmpty()) {
                _state.value = State()
                abandonFocus()
            } else {
                pump()
            }
            true
        }
        if (done) _events.tryEmit(Event.Done(id))
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

        override fun onDone(utteranceId: String) = finish(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            problem = "The phone's voice failed while speaking. Check Settings > Text-to-speech output."
            synchronized(lock) {
                if (paused) return
                queue.firstOrNull { it.id == utteranceId }?.release()
                queue.removeAll { it.id == utteranceId }
                if (queue.isEmpty()) {
                    _state.value = State()
                    abandonFocus()
                } else {
                    pump()
                }
            }
            _events.tryEmit(Event.Failed(utteranceId))
        }

        // Stops are always initiated by us (stop / pause / interrupt) and handled there.
        override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
    }
}
