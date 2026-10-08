package ai.visionmirror.mirror

import ai.visionmirror.app.HardwareKeys
import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.say
import ai.visionmirror.camera.CaptureController
import ai.visionmirror.camera.CapturedPhoto
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.guidance.FaceObservation
import ai.visionmirror.guidance.Guidance
import ai.visionmirror.guidance.MirrorCoach
import ai.visionmirror.haptics.Haptics
import ai.visionmirror.haptics.proximityIntervalMs
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

data class MirrorUiState(
    val guidance: Guidance? = null,
    /** 0..1 framing quality. Read in draw lambdas (Halo, frame glow) rather than recomposed. */
    val quality: Float = 0f,
    val faceVisible: Boolean = false,
    /** 3, 2, 1 while the auto-capture countdown runs. */
    val countdown: Int? = null,
    val capturing: Boolean = false,
    val caption: String = "Hold the phone at arm's length, facing you.",
    /** Guidance and the countdown are on hold; the camera stays live and she can still take the photo. */
    val paused: Boolean = false,
    val facing: CameraFacing = CameraFacing.Front,
)

sealed interface MirrorEvent {
    data class Captured(val photo: CapturedPhoto) : MirrorEvent
}

/**
 * Drives the Mirror screen: speaks guidance, ticks haptics faster as she gets closer, starts a
 * countdown once framing has been good for ~1.2 s, and captures (automatically, by tap, or by
 * volume key). The decisions live in [MirrorCoach] (pure, unit-tested); this class performs them.
 */
@HiltViewModel
class MirrorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speaker: Speaker,
    private val haptics: Haptics,
    private val earcons: Earcons,
    private val keys: HardwareKeys,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val captureController = CaptureController()

    private val _state = MutableStateFlow(MirrorUiState())
    val state: StateFlow<MirrorUiState> = _state

    private val _events = Channel<MirrorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val coach = MirrorCoach()
    private val lock = Any()
    private var active = false
    private var countdownJob: Job? = null
    private var capturing = false

    /** After she cancels a countdown, do not restart it until the framing has broken and re-formed. */
    private var autoSuppressed = false

    private var proximityJob: Job? = null

    init {
        viewModelScope.launch { keys.volume.collect { if (active) onTap() } }
        // The chosen camera is a saved setting, so it is still there next time she opens the app.
        viewModelScope.launch {
            settingsStore.settings.collect { s -> _state.update { it.copy(facing = s.cameraFacing) } }
        }
    }

    /** Pause or resume the spoken guidance and auto-capture. The preview stays on and she can still take the photo. */
    fun togglePause() {
        val nowPaused = synchronized(lock) {
            if (!active || capturing) return
            val p = !_state.value.paused
            if (p) {
                cancelCountdownLocked()
                _state.update { it.copy(paused = true, countdown = null, caption = "Paused. Tap Resume, or take the photo now.") }
            } else {
                coach.reset()
                autoSuppressed = false
                _state.update { it.copy(paused = false, caption = "Hold the phone at arm's length, facing you.") }
            }
            p
        }
        earcons.play(if (nowPaused) Earcon.ListeningOff else Earcon.Ready)
        speaker.say(if (nowPaused) "Paused." else "Guiding again.", Priority.Interrupt)
    }

    /** Switch between the front and back camera. The choice is saved. */
    fun flipCamera() {
        val next = if (_state.value.facing == CameraFacing.Front) CameraFacing.Back else CameraFacing.Front
        synchronized(lock) {
            if (capturing) return
            cancelCountdownLocked()
            coach.reset()
            autoSuppressed = false
            _state.update { it.copy(facing = next, faceVisible = false, guidance = null, quality = 0f) }
        }
        viewModelScope.launch { settingsStore.update { it.copy(cameraFacing = next) } }
        speaker.say(
            if (next == CameraFacing.Back) "Using the back camera." else "Using the front camera.",
            Priority.Interrupt,
        )
    }

    /** Screen is visible and camera is live. */
    fun onEnter() = synchronized(lock) {
        active = true
        keys.captureEnabled = true
        capturing = false
        autoSuppressed = false
        coach.reset()
        _state.value = MirrorUiState(facing = _state.value.facing)
        speaker.say("I'll guide you. Hold the phone at arm's length, facing you.", Priority.Interrupt)
        proximityJob?.cancel()
        proximityJob = viewModelScope.launch {
            while (true) {
                val s = _state.value
                delay(proximityIntervalMs(s.quality))
                val now = _state.value
                // Ticks speed up as she gets closer; they stop once it is perfect (the countdown takes over).
                if (active && !now.paused && now.faceVisible && now.guidance?.good == false) haptics.proximity(now.quality)
            }
        }
    }

    /** Screen left (or app backgrounded): stop everything. */
    fun onExit() = synchronized(lock) {
        active = false
        keys.captureEnabled = false
        cancelCountdownLocked()
        proximityJob?.cancel()
        speaker.stop()
    }

    /** Called from the camera analysis thread for every frame. */
    fun onFace(face: FaceObservation?) {
        val out = synchronized(lock) {
            if (!active || capturing || _state.value.paused) return
            val o = coach.update(face, System.currentTimeMillis())
            _state.update {
                it.copy(
                    guidance = o.framing.guidance,
                    quality = o.framing.quality,
                    faceVisible = face != null,
                    caption = o.framing.guidance.spoken,
                )
            }
            if (!o.framing.guidance.good) {
                autoSuppressed = false
                // Lost the framing mid-countdown: cancel quietly and carry on guiding.
                if (countdownJob != null) cancelCountdownLocked()
            } else if (o.stable && countdownJob == null && !autoSuppressed) {
                startCountdownLocked()
            }
            o
        }
        // Speak/cue outside the lock. Interrupt so a stale instruction never plays late.
        out.speak?.let { if (_state.value.countdown == null) speaker.say(it, Priority.Interrupt) }
        if (out.closerCue) earcons.play(Earcon.Closer)
    }

    /** Full-screen tap or volume key: capture now, or cancel a running countdown. */
    fun onTap() {
        val wasCounting = synchronized(lock) { countdownJob != null }
        if (wasCounting) {
            synchronized(lock) {
                cancelCountdownLocked()
                autoSuppressed = true
            }
            earcons.play(Earcon.ListeningOff)
            speaker.say("Cancelled. Tap anywhere when you're ready.", Priority.Interrupt)
        } else {
            capture()
        }
    }

    private fun startCountdownLocked() {
        earcons.play(Earcon.Ready)
        haptics.ready()
        countdownJob = viewModelScope.launch {
            for (n in 3 downTo 1) {
                _state.update { it.copy(countdown = n) }
                earcons.play(Earcon.CountdownTick)
                haptics.tick()
                delay(1_000)
            }
            _state.update { it.copy(countdown = null) }
            countdownJob = null
            capture()
        }
    }

    private fun cancelCountdownLocked() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { it.copy(countdown = null) }
    }

    private fun capture() {
        synchronized(lock) {
            if (capturing || !active) return
            capturing = true
            cancelCountdownLocked()
            _state.update { it.copy(capturing = true, caption = "Got it.") }
        }
        earcons.play(Earcon.Captured)
        haptics.click()
        speaker.stop()
        viewModelScope.launch {
            val photo = captureController.capture(context)
            if (photo == null) {
                synchronized(lock) {
                    capturing = false
                    autoSuppressed = true
                    _state.update { it.copy(capturing = false) }
                }
                earcons.play(Earcon.Error)
                haptics.error()
                speaker.say("I couldn't take the photo. Tap anywhere to try again.", Priority.Interrupt)
            } else {
                synchronized(lock) { active = false }
                proximityJob?.cancel()
                _events.send(MirrorEvent.Captured(photo))
            }
        }
    }

    override fun onCleared() {
        speaker.stop()
        super.onCleared()
    }
}
