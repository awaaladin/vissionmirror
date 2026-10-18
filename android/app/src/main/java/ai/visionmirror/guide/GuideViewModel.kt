package ai.visionmirror.guide

import ai.visionmirror.assistant.ScreenCommand
import ai.visionmirror.assistant.ScreenCommands
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.say
import ai.visionmirror.haptics.Haptics
import ai.visionmirror.haptics.proximityIntervalMs
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GuideUiState(
    val caption: String = "Starting guide mode.",
    val paused: Boolean = false,
    /** Why guiding cannot work on this phone, when it cannot. */
    val problem: String? = null,
)

/**
 * "Guide me": the back camera watches ahead and she says what is there, where, and roughly how far, a few times a
 * second at most and only when something new appears or comes closer. Everything happens on the phone.
 *
 * It is an extra pair of eyes, never a replacement for a cane or a guide: it cannot see steps, holes, kerbs or glass.
 * She is told so every time it starts.
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speaker: Speaker,
    private val haptics: Haptics,
    private val screenCommands: ScreenCommands,
) : ViewModel() {

    private val _state = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = _state

    private var sensor: ObjectSensor? = null
    private val announcer = GuideAnnouncer()

    @Volatile private var active = false
    @Volatile private var paused = false
    @Volatile private var failed = false
    @Volatile private var startAt = 0L
    private var lastHaptic = 0L

    init {
        viewModelScope.launch {
            screenCommands.flow.collect { c ->
                if (!active) return@collect
                when (c) {
                    ScreenCommand.Pause -> pause(announce = true)
                    ScreenCommand.Hold -> pause(announce = false)
                    ScreenCommand.Resume, ScreenCommand.LookAgain -> resume()
                    else -> Unit
                }
            }
        }
    }

    fun onEnter() {
        active = true
        paused = false
        failed = false
        announcer.reset()
        _state.value = GuideUiState(caption = "Guide mode is on. Say pause to stop, or tap to talk to me.")
        speaker.say(INTRO, Priority.Interrupt)
        // Let her hear the warning before the first announcement talks over it.
        startAt = System.currentTimeMillis() + INTRO_MS
    }

    fun onExit() {
        active = false
        speaker.stop()
        runCatching { sensor?.close() }
        sensor = null
    }

    fun togglePause() = if (paused) resume() else pause(announce = true)

    private fun pause(announce: Boolean) {
        paused = true
        _state.update { it.copy(paused = true, caption = "Paused. Say go on to carry on.") }
        if (announce) {
            speaker.stop()
            speaker.say("Paused.", Priority.Interrupt, local = true)
        }
    }

    private fun resume() {
        paused = false
        announcer.reset()
        startAt = 0L
        _state.update { it.copy(paused = false, caption = "Guiding again.") }
        speaker.say("Guiding again.", Priority.Interrupt, local = true)
    }

    /** Called from the camera thread for each picture. */
    fun onFrame(picture: Bitmap) {
        if (!active || paused || failed || System.currentTimeMillis() < startAt) return
        val s = sensor ?: runCatching { ObjectSensor(context) }.getOrNull()?.also { sensor = it }
        if (s == null) {
            failed = true
            _state.update { it.copy(problem = "Guide mode is not available on this phone.", caption = "Guide mode is not available on this phone.") }
            speaker.say("Guide mode is not available on this phone.", Priority.Interrupt)
            return
        }
        val sightings = GuideLogic.sightings(runCatching { s.detect(picture) }.getOrDefault(emptyList()))
        buzz(sightings)
        val spoken = announcer.next(sightings) ?: return
        _state.update { it.copy(caption = spoken.text) }
        // Old news is worse than silence: only speak over a sentence in progress when it is urgent.
        val busy = speaker.state.value.utteranceId != null
        if (!busy || spoken.urgent) speaker.say(spoken.text, if (spoken.urgent) Priority.Interrupt else Priority.Queue)
    }

    /** Vibration quickens as the nearest thing straight ahead gets closer. */
    private fun buzz(sightings: List<Sighting>) {
        val nearest = sightings.filter { it.zone == Zone.Ahead && it.range != Range.Far }.maxByOrNull { it.range.closeness } ?: return
        val now = System.currentTimeMillis()
        if (now - lastHaptic >= proximityIntervalMs(nearest.range.closeness)) {
            lastHaptic = now
            haptics.proximity(nearest.range.closeness)
        }
    }

    override fun onCleared() {
        onExit()
        super.onCleared()
    }

    private companion object {
        const val INTRO_MS = 9_000L
        const val INTRO =
            "Guide mode is on. I'll tell you about people and objects ahead. I can't see steps, holes or glass, " +
                "so please keep using your cane or guide. Tap to talk to me, or say pause."
    }
}
