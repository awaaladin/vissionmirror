package ai.visionmirror.look

import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.say
import ai.visionmirror.camera.CaptureController
import ai.visionmirror.camera.CapturedPhoto
import ai.visionmirror.haptics.Haptics
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The three things the back camera can do for her besides the mirror. */
enum class LookMode(val route: String, val title: String, val intro: String, val quick: String = "Hold steady.") {
    Read("read", "Read", "Point the camera at the writing. Hold it steady."),
    Around("around", "Look around", "Point the camera ahead of you. Hold it steady."),
    ;

    companion object {
        fun fromRoute(route: String?): LookMode = entries.firstOrNull { it.route == route } ?: Read
    }
}

data class LookUiState(
    val mode: LookMode = LookMode.Read,
    val caption: String = "",
    /** The scan line is sweeping: it is looking right now. */
    val scanning: Boolean = false,
    val busy: Boolean = false,
)

sealed interface LookEvent {
    /** "Look around" needs the AI: hand the photo to the session, which describes it. */
    data class Describe(val photo: CapturedPhoto) : LookEvent
}

/**
 * Point, hold steady for a moment, hear the answer. Reading is worked out on the phone (no internet, and the
 * picture never leaves it); "look around" goes to the AI because it needs real understanding of a scene.
 */
@HiltViewModel
class LookViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speaker: Speaker,
    private val earcons: Earcons,
    private val haptics: Haptics,
) : ViewModel() {

    val captureController = CaptureController()

    private val _state = MutableStateFlow(LookUiState())
    val state: StateFlow<LookUiState> = _state

    private val _events = Channel<LookEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var cycle: Job? = null

    /** Screen is visible and the camera is live. */
    fun onEnter(mode: LookMode) {
        _state.value = LookUiState(mode = mode, caption = mode.intro)
        startCycle(intro = true)
    }

    fun onExit() {
        cycle?.cancel()
        cycle = null
        speaker.stop()
    }

    /** Tap anywhere: try again now (or cancel nothing; the cycle simply restarts). */
    fun onTap() {
        if (_state.value.busy) return
        startCycle(intro = false)
    }

    private fun startCycle(intro: Boolean) {
        cycle?.cancel()
        cycle = viewModelScope.launch {
            val mode = _state.value.mode
            // One short phrase in the phone's own voice (instant), then scan for about a second: it should feel immediate.
            speaker.say(mode.quick, Priority.Interrupt, local = true)
            _state.update { it.copy(caption = mode.intro, busy = false, scanning = true) }
            haptics.tick()
            delay(if (intro) 1_500 else 1_100)
            _state.update { it.copy(scanning = false, busy = true, caption = "Looking...") }
            earcons.play(Earcon.Captured)
            haptics.click()
            speaker.stop()
            val photo = captureController.capture(context)
            if (photo == null) {
                fail("I couldn't take the picture. Tap anywhere to try again.")
                return@launch
            }
            when (mode) {
                LookMode.Read -> finish(readAloud(photo))
                LookMode.Around -> {
                    _state.update { it.copy(busy = false) }
                    _events.send(LookEvent.Describe(photo))
                }
            }
        }
    }

    private suspend fun readAloud(photo: CapturedPhoto): String {
        val text = TextReader.read(photo.bitmap)
        return if (text.isBlank()) {
            "I can't see any writing. Move a little closer, make sure there is light, and tap to try again."
        } else {
            "It says: " + text.take(MAX_READ_CHARS)
        }
    }

    private fun finish(line: String) {
        _state.update { it.copy(busy = false, scanning = false, caption = line) }
        earcons.play(Earcon.Ready)
        speaker.say("$line Tap anywhere to look again.", Priority.Interrupt)
    }

    private fun fail(line: String) {
        _state.update { it.copy(busy = false, scanning = false, caption = line) }
        earcons.play(Earcon.Error)
        haptics.error()
        speaker.say(line, Priority.Interrupt)
    }

    override fun onCleared() {
        speaker.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_READ_CHARS = 1_500
    }
}
