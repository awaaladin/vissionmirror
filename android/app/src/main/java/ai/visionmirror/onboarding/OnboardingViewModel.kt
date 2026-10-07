package ai.visionmirror.onboarding

import ai.visionmirror.app.Spoken
import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.VoiceCommand
import ai.visionmirror.audio.VoiceCommands
import ai.visionmirror.audio.VoiceEvent
import ai.visionmirror.audio.VoiceInput
import ai.visionmirror.audio.say
import ai.visionmirror.data.net.PermissionChecker
import ai.visionmirror.data.settings.SettingsStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { Welcome, Camera, Microphone, Done }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** Shown in large type and mirrored by the spoken line. */
    val caption: String = "${Spoken.WELCOME} ${Spoken.PRIVACY}",
)

sealed interface OnboardingEvent {
    data object RequestCamera : OnboardingEvent
    data object RequestMicrophone : OnboardingEvent
    data object Finished : OnboardingEvent
}

/**
 * Welcome, then each permission is explained aloud *before* its system dialog appears.
 * One huge Continue target (or the spoken word "continue" once the mic is allowed).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val speaker: Speaker,
    private val earcons: Earcons,
    private val voice: VoiceInput,
    private val permissions: PermissionChecker,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var started = false
    private var listening = false

    init {
        viewModelScope.launch { voice.events.collect(::onVoice) }
        viewModelScope.launch {
            // Once a line has finished, offer the voice command (only possible if the mic is allowed).
            // collectLatest + a short pause: only listen once the whole queue has really gone quiet,
            // otherwise the microphone would hear the app's own next sentence.
            speaker.events.filterIsInstance<SpeechManager.Event.Done>().collectLatest {
                delay(500)
                if (!speaker.state.value.isSpeaking) listenForContinue()
            }
        }
    }

    fun onEnter() {
        if (started) return
        started = true
        speaker.say(Spoken.WELCOME, Priority.Interrupt)
        speaker.say(Spoken.PRIVACY)
        speaker.say(Spoken.CONTINUE_HINT)
    }

    /** The big Continue target (or the spoken command). */
    fun onContinue() {
        stopListening()
        when (_state.value.step) {
            OnboardingStep.Welcome -> goTo(OnboardingStep.Camera)
            OnboardingStep.Camera ->
                if (permissions.hasCamera()) goTo(OnboardingStep.Microphone)
                else viewModelScope.launch { _events.send(OnboardingEvent.RequestCamera) }
            OnboardingStep.Microphone ->
                if (permissions.hasMic()) goTo(OnboardingStep.Done)
                else viewModelScope.launch { _events.send(OnboardingEvent.RequestMicrophone) }
            OnboardingStep.Done -> finish()
        }
    }

    fun onCameraResult(granted: Boolean) {
        if (granted) {
            goTo(OnboardingStep.Microphone)
        } else {
            earcons.play(Earcon.Error)
            say(Spoken.CAMERA_DENIED)
        }
    }

    fun onMicrophoneResult(granted: Boolean) {
        // The microphone is optional: she can still use every button without it.
        if (!granted) say(Spoken.MIC_DENIED)
        goTo(OnboardingStep.Done)
    }

    private fun goTo(step: OnboardingStep) {
        val caption = when (step) {
            OnboardingStep.Welcome -> "${Spoken.WELCOME} ${Spoken.PRIVACY}"
            OnboardingStep.Camera -> Spoken.CAMERA_WHY
            OnboardingStep.Microphone -> Spoken.MIC_WHY
            OnboardingStep.Done -> Spoken.ALL_SET
        }
        _state.update { OnboardingUiState(step, caption) }
        say(caption)
        if (step == OnboardingStep.Done) {
            // Nothing more to ask: finish once the line has been spoken (or immediately if muted).
            viewModelScope.launch {
                settings.update { it.copy(onboardingDone = true) }
                withTimeoutOrNull(8_000) { speaker.events.filterIsInstance<SpeechManager.Event.Done>().first() }
                _events.send(OnboardingEvent.Finished)
            }
        }
    }

    private fun finish() {
        viewModelScope.launch {
            settings.update { it.copy(onboardingDone = true) }
            _events.send(OnboardingEvent.Finished)
        }
    }

    private fun say(text: String) {
        speaker.say(text, Priority.Interrupt)
        if (_state.value.step != OnboardingStep.Done) speaker.say(Spoken.CONTINUE_HINT)
    }

    // ---- voice "continue" ------------------------------------------------------------------

    private fun listenForContinue() {
        if (listening || !permissions.hasMic() || _state.value.step == OnboardingStep.Done) return
        listening = true
        voice.start()
    }

    private fun stopListening() {
        if (listening) voice.cancel()
        listening = false
    }

    private fun onVoice(e: VoiceEvent) {
        if (!listening) return
        when (e) {
            is VoiceEvent.Final -> {
                listening = false
                if (VoiceCommands.parse(e.text) == VoiceCommand.Continue) onContinue()
            }
            is VoiceEvent.Failed -> listening = false
            else -> Unit
        }
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }
}
