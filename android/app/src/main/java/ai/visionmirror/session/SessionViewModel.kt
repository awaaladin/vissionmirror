package ai.visionmirror.session

import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.ResponseLanguage
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.VoiceCommand
import ai.visionmirror.audio.VoiceCommands
import ai.visionmirror.audio.VoiceEvent
import ai.visionmirror.audio.VoiceFailure
import ai.visionmirror.audio.VoiceInput
import ai.visionmirror.audio.WakeEvent
import ai.visionmirror.audio.WakeSource
import ai.visionmirror.audio.WakeWord
import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.ControlHints
import ai.visionmirror.assistant.VoiceCatalog
import ai.visionmirror.assistant.AssistantIntents
import ai.visionmirror.assistant.ClockAnswer
import ai.visionmirror.assistant.HandsFree
import ai.visionmirror.assistant.OwnSpeech
import ai.visionmirror.assistant.PermissionRequests
import ai.visionmirror.assistant.StopWords
import ai.visionmirror.emergency.ContactBook
import ai.visionmirror.emergency.Emergency
import ai.visionmirror.emergency.EmergencyIntent
import ai.visionmirror.emergency.EmergencyIntents
import ai.visionmirror.emergency.PhoneActions
import ai.visionmirror.assistant.ScreenCommand
import ai.visionmirror.assistant.ScreenCommands
import ai.visionmirror.assistant.DeviceControls
import ai.visionmirror.assistant.SettingChange
import ai.visionmirror.assistant.VoiceSettings
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AskResponse
import ai.visionmirror.data.api.DescribeResponse
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.net.Connectivity
import ai.visionmirror.data.net.PermissionChecker
import ai.visionmirror.data.repo.MirrorRepository
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.di.ApplicationScope
import ai.visionmirror.haptics.Haptics
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Phase {
    Idle,

    /** Photo sent, waiting for the description. */
    Analysing,
    Result,

    /** The AI said the photo was unusable (too dark, no person...). Advice was read aloud. */
    Unusable,

    /** No internet: the photo waits in memory and is described when we are back online. */
    Offline,
    Failed,
}

enum class AskPhase { Idle, Listening, Thinking }

data class QA(val question: String, val answer: String)

data class SessionUiState(
    val phase: Phase = Phase.Idle,
    /** Upright preview of the photo, in memory only. */
    val photo: Bitmap? = null,
    val result: DescribeResponse? = null,
    val detail: DetailLevel = DetailLevel.Standard,
    /** What the screen shows and "Repeat" reads: the description, then the latest answer. */
    val displayText: String = "",
    val conversation: List<QA> = emptyList(),
    val ask: AskPhase = AskPhase.Idle,
    val partialQuestion: String = "",
    /** Re-describing at another detail level while the old result stays on screen. */
    val refreshing: Boolean = false,
    /** Spoken-error twin shown on screen. */
    val banner: String? = null,
)

sealed interface SessionEvent {
    /** The photo expired on the server: go back to the mirror. */
    data object NeedNewPhoto : SessionEvent
    data object Retake : SessionEvent
}

/**
 * Describe + Ask for one photo. Activity-scoped so the Analysing and Result screens (and the
 * shared-element transitions between them) see the same state.
 *
 * Privacy: the photo lives only in memory, and the server session is deleted when she retakes,
 * leaves, or uses "delete session data".
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repo: MirrorRepository,
    private val speaker: Speaker,
    private val earcons: Earcons,
    private val haptics: Haptics,
    private val voice: VoiceInput,
    private val connectivity: Connectivity,
    private val settings: SettingsStore,
    private val permissions: PermissionChecker,
    private val wake: WakeSource,
    private val device: DeviceControls,
    private val screenCommands: ScreenCommands,
    private val own: OwnSpeech,
    private val emergency: Emergency,
    private val contactBook: ContactBook,
    private val phone: PhoneActions,
    private val permissionRequests: PermissionRequests,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    /** Who is talking (Adanna or Bola): the Home screen and greetings use the name. */
    private val _persona = MutableStateFlow(ai.visionmirror.audio.Persona.Adanna)
    val persona: StateFlow<ai.visionmirror.audio.Persona> = _persona

    /** Live proof that she is being heard while the app waits for her name (words, loudness, status). */
    val hearing: StateFlow<ai.visionmirror.audio.Hearing> = wake.hearing

    /** Mic level 0..1 for the Quartz ripples. A separate flow so ~30 updates/s never recompose the screen. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    private val _events = Channel<SessionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Places her voice asked to go ("Adanna, read this"). The navigation host acts on them. */
    private val _actions = MutableSharedFlow<AssistantAction>(extraBufferCapacity = 4)
    val actions: SharedFlow<AssistantAction> = _actions.asSharedFlow()

    /** Command = the short window after a reading; Assistant = right after she said "Adanna". */
    private enum class ListenMode { None, Question, Command, Assistant }

    private var listenMode = ListenMode.None
    private var jpeg: ByteArray? = null
    private var sessionId: String? = null
    private var describeJob: Job? = null
    private var lastDescriptionSpeechId: String? = null
    private var voiceCommandsEnabled = true
    private var language = ResponseLanguage.NigerianEnglish
    private var sceneMode = false
    private var pendingMic: String? = null

    /** From "Adanna" until her request has been handled: the wake listener stays off the microphone. */
    private var assistantTurn = false
    private var assistantAttempts = 0
    private var pendingReply: ((String) -> Unit)? = null
    private var sosJob: Job? = null
    private var handsFree = false
    private var screen: String? = null
    private var silentCycles = 0

    /** Replaceable in tests so the patience window runs on test time. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private fun onCameraScreen() = screen == "mirror" || screen == "guide" || screen?.startsWith("look") == true
    private var assistantStartedAt = 0L

    init {
        viewModelScope.launch {
            settings.settings.collectLatest { s ->
                voiceCommandsEnabled = s.voiceCommands
                language = s.language
                handsFree = s.handsFree
                _persona.value = s.persona
                if (_state.value.phase == Phase.Idle) _state.update { it.copy(detail = s.detailLevel) }
            }
        }
        viewModelScope.launch { voice.events.collect(::onVoiceEvent) }
        viewModelScope.launch { wake.wakes.collect(::onWake) }
        viewModelScope.launch { wake.sleeps.collect { goToSleep() } }
        // The microphone opens the moment "Listening." has been said.
        viewModelScope.launch {
            speaker.events.collect { e ->
                val id = pendingMic
                if (id != null && e.eventId() == id && (e is SpeechManager.Event.Done || e is SpeechManager.Event.Failed)) {
                    openAssistantMic()
                } else if (e is SpeechManager.Event.Done) {
                    maybeHandsFree()
                }
            }
        }
        // "Stop" or her name while she is talking.
        viewModelScope.launch {
            wake.stops.collect {
                speaker.stop()
                if (sosJob?.isActive == true) cancelSos()
                // On the camera screens "stop" or "pause" also holds the scan or the countdown, then listens: the
                // person cannot be expected to find a button, so saying "pause" is all it takes to give a request.
                if (onCameraScreen()) {
                    screenCommands.send(ScreenCommand.Pause)
                    if (!assistantTurn && listenMode == ListenMode.None && permissions.hasMic()) openTurnWith("Paused. I'm listening.")
                }
            }
        }
        viewModelScope.launch { wake.resumes.collect { if (onCameraScreen()) screenCommands.send(ScreenCommand.Resume) } }
        // Offline queue: the moment we are online again, describe the waiting photo.
        viewModelScope.launch {
            connectivity.online.distinctUntilChanged().collect { online ->
                if (online && _state.value.phase == Phase.Offline && jpeg != null) {
                    speaker.say("You're back online. Looking at you now.", Priority.Interrupt)
                    _state.update { it.copy(phase = Phase.Analysing, banner = null) }
                    startDescribe()
                }
            }
        }
        // After the description has been read, listen once for a spoken command.
        viewModelScope.launch {
            speaker.events.filterIsInstance<SpeechManager.Event.Done>().collect { done ->
                if (done.id == lastDescriptionSpeechId) maybeListenForCommand()
            }
        }
    }

    // ---- describe --------------------------------------------------------------------------

    /** [scene] = the photo is of surroundings ("look around"), not of her. */
    fun submitPhoto(jpegBytes: ByteArray, preview: Bitmap?, scene: Boolean = false) {
        endRemoteSession()
        jpeg = jpegBytes
        sceneMode = scene
        _state.update {
            it.copy(
                phase = Phase.Analysing, photo = preview, result = null, displayText = "",
                conversation = emptyList(), ask = AskPhase.Idle, banner = null, refreshing = false,
            )
        }
        speaker.say(if (scene) "One moment. Let me see." else "One moment. I'm looking at you.", Priority.Interrupt)
        startDescribe()
    }

    private fun startDescribe(attempt: Int = 0, networkAttempt: Int = 0) {
        val bytes = jpeg ?: return
        val detail = _state.value.detail
        describeJob?.cancel()
        describeJob = viewModelScope.launch {
            repo.describe(bytes, detail, language.apiTag, sceneMode)
                .onSuccess(::onDescribed)
                .onFailure { onDescribeFailed(it, attempt, networkAttempt) }
        }
    }

    private fun onDescribed(r: DescribeResponse) {
        val old = sessionId
        sessionId = r.sessionId
        if (old != null && old != r.sessionId) appScope.launch { repo.deleteSession(old) }

        if (!r.imageQuality.usable) {
            _state.update {
                it.copy(phase = Phase.Unusable, result = null, displayText = r.spokenText, refreshing = false, banner = null)
            }
            earcons.play(Earcon.Error)
            speaker.say(r.spokenText, Priority.Interrupt)
            return
        }
        _state.update {
            it.copy(phase = Phase.Result, result = r, displayText = r.spokenText, refreshing = false, banner = null)
        }
        earcons.play(Earcon.Ready)
        haptics.ready()
        lastDescriptionSpeechId = speaker.say(r.spokenText, Priority.Interrupt)
    }

    private suspend fun onDescribeFailed(t: Throwable, attempt: Int, networkAttempt: Int) {
        val error = t as? AppError ?: AppError.Unexpected(t)
        val hadResult = _state.value.result != null // refreshing detail level: keep what she already has
        when {
            error is AppError.NetworkTimeout && networkAttempt < MAX_NETWORK_RETRIES -> {
                retrySlowNetwork(attempt, networkAttempt)
            }
            error is AppError.Offline && connectivity.online.first() && networkAttempt < MAX_NETWORK_RETRIES -> {
                retrySlowNetwork(attempt, networkAttempt)
            }
            error is AppError.Offline -> {
                if (hadResult) failInPlace(error) else {
                    _state.update { it.copy(phase = Phase.Offline, banner = error.spokenText) }
                    earcons.play(Earcon.Error)
                    speaker.say(error.spokenText, Priority.Interrupt)
                }
            }
            error is AppError.RateLimited && attempt < MAX_RATE_RETRIES -> {
                _state.update { it.copy(banner = error.spokenText) }
                speaker.say(error.spokenText, Priority.Interrupt)
                delay(error.retryAfterSeconds * 1_000L)
                startDescribe(attempt + 1, networkAttempt)
            }
            hadResult -> failInPlace(error)
            else -> {
                _state.update { it.copy(phase = Phase.Failed, banner = error.spokenText, refreshing = false) }
                earcons.play(Earcon.Error)
                haptics.error()
                speaker.say(error.spokenText, Priority.Interrupt)
            }
        }
    }

    private suspend fun retrySlowNetwork(attempt: Int, networkAttempt: Int) {
        val message = "Your connection is slow. I'll keep trying."
        _state.update { it.copy(banner = message) }
        speaker.say(message, Priority.Interrupt)
        delay(1_000L shl networkAttempt)
        startDescribe(attempt, networkAttempt + 1)
    }

    private fun failInPlace(error: AppError) {
        _state.update { it.copy(refreshing = false, banner = error.spokenText, ask = AskPhase.Idle) }
        earcons.play(Earcon.Error)
        haptics.error()
        speaker.say(error.spokenText, Priority.Interrupt)
    }

    /** "Try again" after a failure. */
    fun retry() {
        if (jpeg == null) return
        speaker.say("Trying again.", Priority.Interrupt)
        _state.update { it.copy(phase = Phase.Analysing, banner = null) }
        startDescribe()
    }

    /** Brief / Standard / Detailed. Re-describes the same photo; the old result stays up meanwhile. */
    fun changeDetail(level: DetailLevel) {
        if (level == _state.value.detail) return
        _state.update { it.copy(detail = level) }
        if (jpeg == null) return
        if (_state.value.phase == Phase.Result || _state.value.phase == Phase.Unusable) {
            _state.update { it.copy(refreshing = true, banner = null) }
            speaker.say("${level.label} description.", Priority.Interrupt)
            startDescribe()
        }
    }

    // ---- reading controls ------------------------------------------------------------------

    fun repeat() {
        val text = _state.value.displayText
        if (text.isNotBlank()) lastDescriptionSpeechId = speaker.say(text, Priority.Interrupt)
    }

    fun pauseOrResume() {
        if (speaker.state.value.isPaused) speaker.resume() else speaker.pause()
    }

    // ---- ask -------------------------------------------------------------------------------

    /** Tap on Ask, press-and-hold anywhere, or the spoken command "ask". */
    fun startAsking() {
        if (sessionId == null) {
            speaker.say("There's no photo to ask about. Let's take a new one.", Priority.Interrupt)
            return
        }
        if (!permissions.hasMic()) {
            speaker.say("I need the microphone to hear you. You can allow it in your phone's settings.", Priority.Interrupt)
            return
        }
        speaker.stop()
        listenMode = ListenMode.Question
        assistantAttempts = 0
        assistantStartedAt = clock()
        _state.update { it.copy(ask = AskPhase.Listening, partialQuestion = "", banner = null) }
        earcons.play(Earcon.ListeningOn)
        haptics.click()
        voice.start(language.recognitionTag())
    }

    /** Hold-to-talk release: deliver what was heard. */
    fun finishAsking() {
        if (listenMode == ListenMode.Question) voice.finish()
    }

    @Volatile private var inBackground = false

    /** Asleep: say so, then go completely quiet until her name is heard again. */
    private fun goToSleep() {
        if (wake.asleep.value) return
        cancelAsking()
        pendingReply = null
        _contactDraft.value = null
        if (onCameraScreen()) screenCommands.send(ScreenCommand.Pause)
        val name = _persona.value.displayName
        wake.setAsleep(true)
        val id = speaker.say("Going to sleep. Say $name when you want me.", Priority.Interrupt, local = true)
        viewModelScope.launch {
            // Let the goodnight finish, then silence everything (guidance on the camera screens included).
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                speaker.events.first { (it is SpeechManager.Event.Done || it is SpeechManager.Event.Failed) && it.eventId() == id }
            }
            if (wake.asleep.value) speaker.setSilenced(true)
        }
    }

    /** The app went out of sight: stop talking, stop listening, and forget the turn that was in progress. */
    fun onBackground() {
        inBackground = true
        _contactDraft.value = null
        cancelAsking()
        pendingReply = null
        speaker.stop()
    }

    fun onForeground() { inBackground = false }

    fun cancelAsking() {
        pendingMic = null
        endAssistantTurn()
        voice.cancel()
        listenMode = ListenMode.None
        _micLevel.value = 0f
        _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
    }

    private fun maybeListenForCommand() {
        if (inBackground || !voiceCommandsEnabled || !permissions.hasMic()) return
        if (_state.value.phase != Phase.Result || _state.value.ask != AskPhase.Idle) return
        listenMode = ListenMode.Command
        voice.start(language.recognitionTag())
    }

    private fun onVoiceEvent(e: VoiceEvent) {
        when (e) {
            VoiceEvent.Ready -> if (listenMode == ListenMode.Command) earcons.play(Earcon.ListeningOn)
            // SpeechStarted is deliberately ignored: she is only heard after the words "special request".
            VoiceEvent.SpeechStarted -> Unit
            is VoiceEvent.Level -> _micLevel.value = e.level
            is VoiceEvent.Partial -> if (listenMode == ListenMode.Question || listenMode == ListenMode.Assistant) _state.update { it.copy(partialQuestion = e.text) }
            is VoiceEvent.Final -> onHeard(e.text)
            is VoiceEvent.Failed -> onVoiceFailed(e.reason, e.code)
        }
    }

    private fun onHeard(text: String) {
        // If the mode was lost to a stray event but she is mid-turn with the assistant, what she said is still her request.
        // The microphone can pick up her own voice. That is never the user speaking: treat it as silence.
        if (own.isEcho(text) && (listenMode != ListenMode.None || assistantTurn)) {
            onVoiceFailed(VoiceFailure.NoSpeech)
            return
        }
        val mode = if (listenMode == ListenMode.None && assistantTurn) ListenMode.Assistant else listenMode
        listenMode = ListenMode.None
        _micLevel.value = 0f
        when (mode) {
            ListenMode.Question -> {
                earcons.play(Earcon.ListeningOff)
                submitQuestion(text)
            }
            ListenMode.Command -> {
                earcons.play(Earcon.ListeningOff)
                // The command window also answers to her name: "Adanna, is my shirt tucked in".
                WakeWord.matches(text)?.let { handleWake(it.request, it.called); return }
                when (VoiceCommands.parse(text)) {
                    VoiceCommand.Ask -> startAsking()
                    VoiceCommand.Retake -> retake()
                    VoiceCommand.Repeat -> repeat()
                    VoiceCommand.Pause -> speaker.pause()
                    VoiceCommand.Resume -> speaker.resume()
                    VoiceCommand.Stop -> speaker.stop()
                    else -> Unit // not a command: stay quiet rather than guess
                }
            }
            ListenMode.Assistant -> {
                endAssistantTurn()
                silentCycles = 0
                earcons.play(Earcon.ListeningOff)
                _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
                handleRequest(text)
            }
            ListenMode.None -> Unit
        }
    }

    private fun onVoiceFailed(reason: VoiceFailure, code: Int = 0) {
        val mode = if (listenMode == ListenMode.None && assistantTurn) ListenMode.Assistant else listenMode
        listenMode = ListenMode.None
        _micLevel.value = 0f
        // She said nothing yet (or the recogniser stumbled). Quietly listen again instead of giving up straight away, in every
        // listening mode: the phone's recogniser often gives up within a second, and "I can't hear you" must only come after
        // she has really had time to speak.
        val worthAnotherTry = reason == VoiceFailure.NoSpeech || reason == VoiceFailure.Busy ||
            reason == VoiceFailure.Other || reason == VoiceFailure.Network
        if ((mode == ListenMode.Assistant || mode == ListenMode.Question) && worthAnotherTry &&
            assistantAttempts < ASSISTANT_RETRIES && clock() - assistantStartedAt < (if (_contactDraft.value != null) CONTACT_PATIENCE_MS else ASSISTANT_PATIENCE_MS)
        ) {
            assistantAttempts++
            listenMode = mode
            viewModelScope.launch {
                delay(ASSISTANT_RETRY_GAP_MS)
                if (listenMode == mode) voice.start(language.recognitionTag())
            }
            return
        }
        if (mode == ListenMode.Question) {
            _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
            earcons.play(Earcon.ListeningOff)
            val line = when (reason) {
                VoiceFailure.NoSpeech -> "I didn't catch that. Tap ask and try again."
                VoiceFailure.NoPermission -> "I need the microphone to hear you. You can allow it in your phone's settings."
                VoiceFailure.Network -> "I couldn't hear you because the speech service is offline. Try again in a moment."
                VoiceFailure.Unavailable -> "This phone has no speech recogniser I can use."
                else -> "Something went wrong with the microphone. Try again."
            }
            _state.update { it.copy(banner = line) }
            speaker.say(line, Priority.Interrupt)
        }
        if (mode == ListenMode.Assistant) {
            endAssistantTurn()
            val dropped = pendingReply
            pendingReply = null // she did not answer in time; the question is dropped
            if (_contactDraft.value != null && dropped != null) {
                // In the emergency contact conversation, saying nothing for a while is not the end: the step decides what to do.
                _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
                dropped("")
                return
            }
            if (handsFree && reason == VoiceFailure.NoSpeech) {
                // Hands-free listens again on its own: no scolding when she simply has nothing to say yet.
                _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
                earcons.play(Earcon.ListeningOff)
                silentCycles++
                if (silentCycles < HANDS_FREE_QUIET_CYCLES) {
                    viewModelScope.launch { delay(600); maybeHandsFree() }
                } else {
                    silentCycles = 0
                    speaker.say("I'm here when you need me. Say ${_persona.value.displayName}.", Priority.Interrupt, local = true)
                }
                return
            }
            val line = if (reason == VoiceFailure.NoSpeech) "I didn't catch that. Say my name and try again." else "I couldn't hear you just now. Say my name and try again."
            _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "", banner = if (code != 0) "$line (speech error $code)" else line) }
            earcons.play(Earcon.ListeningOff)
            speaker.say(line, Priority.Interrupt, local = true)
        }
        // A failed command window is silent on purpose: she did not ask for anything.
    }

    // ---- assistant: "Adanna ..." -------------------------------------------------------------

    /** The wake word was heard anywhere in the app. [request] is what she said after it, if anything. */
    private fun onWake(event: WakeEvent) {
        // Asleep, her name wakes the assistant, and what she said after it is then handled as usual.
        if (wake.asleep.value) {
            wake.setAsleep(false)
            speaker.setSilenced(false)
        }
        if (listenMode == ListenMode.Question || listenMode == ListenMode.Assistant) return
        haptics.click()
        handleWake(event.request, event.called)
    }

    private fun endAssistantTurn() {
        assistantTurn = false
        wake.hold(false)
    }

    private fun handleWake(request: String, called: ai.visionmirror.audio.Persona? = null) {
        // If she called the OTHER name, she answers as herself: "I am Bola." She does not pretend to be who was called.
        // ...unless the request is itself a setting ("switch to Adanna"): that is an order, not a mistaken name.
        val isSetting = request.isNotBlank() && VoiceSettings.parse(request) != null
        val wrongName = called != null && called != _persona.value && !isSetting
        if (request.isNotBlank() && !wrongName) {
            speaker.stop()
            handleRequest(request)
            return
        }
        if (!permissions.hasMic()) {
            speaker.say("I need the microphone to hear you. You can allow it in your phone's settings.", Priority.Interrupt, local = true)
            return
        }
        val name = _persona.value.displayName
        openTurnWith(if (wrongName) "I am $name. Listening." else "Listening.")
    }

    /**
     * Say [ack], then listen for what she says next. The microphone opens when she has finished saying it, so the
     * recogniser never hears the assistant; if speech is broken, a timer opens it anyway.
     */
    private fun openTurnWith(ack: String) {
        speaker.stop()
        assistantTurn = true
        assistantAttempts = 0
        assistantStartedAt = clock()
        wake.hold(true) // nothing else may use the microphone until her request is in
        listenMode = ListenMode.Assistant
        _state.update { it.copy(ask = AskPhase.Listening, partialQuestion = "", banner = null) }
        earcons.play(Earcon.ListeningOn)
        val id = speaker.say(ack, Priority.Interrupt, local = ack.length < 40)
        pendingMic = id
        viewModelScope.launch {
            delay(MIC_FALLBACK_MS)
            if (pendingMic == id) openAssistantMic()
        }
    }

    private fun openAssistantMic() {
        pendingMic = null
        if (inBackground || listenMode != ListenMode.Assistant) { endAssistantTurn(); return }
        // A beat of quiet after "Listening." so the end of my own voice is not what the microphone hears first.
        viewModelScope.launch {
            delay(MIC_SETTLE_MS)
            if (listenMode == ListenMode.Assistant) {
                assistantStartedAt = clock()
                voice.start(language.recognitionTag())
            }
        }
    }

    /** What she asked for: a control word, a place to go, or (when there is a photo) a question about it. */
    private fun handleRequest(raw: String) {
        val text = WakeWord.strip(raw)
        val hasPhoto = sessionId != null
        // An answer to a question I just asked ("Say yes to save"): it belongs to that question, nothing else.
        pendingReply?.let { reply -> pendingReply = null; reply(text); return }
        // "Sleep": quiet, and deaf to everything but her name, until she calls.
        if (VoiceCatalog.installed && VoiceCatalog.match(text, only = setOf("wake.sleep")) != null) { goToSleep(); return }
        // "Cancel" during the emergency countdown stops it, however it was heard.
        if (sosJob?.isActive == true && StopWords.matches(text)) { speaker.stop(); cancelSos(); return }
        EmergencyIntents.parse(text)?.let { handleEmergency(it); return }
        // Settings first: "stop listening for my name" is a setting, not the command "stop".
        // On the camera screens, "pause"/"stop" holds the scan or countdown and "go on" continues it.
        if (onCameraScreen()) {
            when (VoiceCommands.parse(text)) {
                VoiceCommand.Stop, VoiceCommand.Pause -> { speaker.stop(); screenCommands.send(ScreenCommand.Pause); return }
                VoiceCommand.Resume, VoiceCommand.Continue -> { screenCommands.send(ScreenCommand.Resume); return }
                else -> Unit
            }
        }
        AssistantIntents.parse(text, hasPhoto, screen)
            ?.takeIf { it == AssistantAction.SnapSelfie || it == AssistantAction.UseFrontCamera || it == AssistantAction.UseBackCamera }
            ?.let { routeAction(it); return }
        VoiceSettings.parse(text)?.let { applySetting(it); return }
        HandsFree.parse(text)?.let { applyHandsFree(it); return }
        ClockAnswer.answer(text, java.time.ZonedDateTime.now())?.let { speaker.say(it, Priority.Interrupt); return }
        VoiceCommands.parse(text)?.let { command ->
            when (command) {
                VoiceCommand.Stop -> { speaker.stop(); return }
                VoiceCommand.Retake -> if (hasPhoto) { retake(); return }
                VoiceCommand.Repeat -> if (hasPhoto) { repeat(); return }
                VoiceCommand.Pause -> if (hasPhoto) { speaker.pause(); return }
                VoiceCommand.Resume -> if (hasPhoto) { speaker.resume(); return }
                VoiceCommand.Ask -> if (hasPhoto) { startAsking(); return }
                VoiceCommand.Continue -> Unit
            }
            Unit
        }
        AssistantIntents.parse(text, hasPhoto, screen)?.let { routeAction(it); return }
        // The app CAN change these. If the words were not recognised, a hint is better than the AI saying it cannot.
        ControlHints.forRequest(text, hasPhoto)?.let { speaker.say(it, Priority.Interrupt); return }
        if (hasPhoto && !AssistantIntents.isKnowledgeQuestion(text)) submitQuestion(text) else askAssistant(text)
    }

    /** The weather and questions about the world. Works with or without a photo. */
    private fun askAssistant(query: String) {
        speaker.say("One moment.", Priority.Interrupt, local = true)
        _state.update { it.copy(ask = AskPhase.Thinking, partialQuestion = query) }
        viewModelScope.launch {
            repo.assist(query, language.apiTag)
                .onSuccess { r ->
                    _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "", banner = null) }
                    earcons.play(Earcon.Ready)
                    speaker.say(r.spokenText, Priority.Interrupt)
                }
                .onFailure { t ->
                    val error = t as? AppError ?: AppError.Unexpected(t)
                    _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "", banner = error.spokenText) }
                    earcons.play(Earcon.Error)
                    speaker.say(error.spokenText, Priority.Interrupt)
                }
        }
    }

    // ---- where am I, and tell someone -----------------------------------------------------------

    private fun handleEmergency(intent: EmergencyIntent) {
        when (intent) {
            EmergencyIntent.WhereAmI -> if (ensure(location = true)) viewModelScope.launch {
                speaker.say("One moment.", Priority.Interrupt, local = true)
                speaker.say(emergency.whereAmI(), Priority.Interrupt)
            }
            EmergencyIntent.Sos -> sos()
            is EmergencyIntent.Share -> share(intent.name)
            is EmergencyIntent.SetContact -> setContact(intent.name)
            EmergencyIntent.ListContacts -> viewModelScope.launch {
                val contacts = settings.settings.first().emergencyContacts
                speaker.say(
                    if (contacts.isEmpty()) "You have no emergency contact yet." else "Your emergency contacts: " + contacts.joinToString(", ") { it.name } + ".",
                    Priority.Interrupt,
                )
            }
            EmergencyIntent.StartSetContact -> startContactDialog()
            EmergencyIntent.ClearContacts -> askConfirm("Remove all your emergency contacts? Say yes to confirm.") {
                viewModelScope.launch {
                    settings.update { it.copy(emergencyContacts = emptyList()) }
                    speaker.say("Done. Your emergency contacts are removed.", Priority.Interrupt)
                }
            }
            EmergencyIntent.CallEmergency -> {
                phone.dial("112")
                speaker.say("Opening the phone with 112. Tap the green button to call.", Priority.Interrupt)
            }
        }
    }

    /** True when every permission needed is granted; otherwise says why it is needed, shows Android's box, and returns false. */
    private fun ensure(location: Boolean = false, sms: Boolean = false, contacts: Boolean = false): Boolean {
        val missing = mutableListOf<String>()
        val words = mutableListOf<String>()
        if (location && !permissions.hasLocation()) {
            missing += listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            words += "your location"
        }
        if (sms && !permissions.hasSms()) { missing += android.Manifest.permission.SEND_SMS; words += "send text messages" }
        if (contacts && !permissions.hasContacts()) { missing += android.Manifest.permission.READ_CONTACTS; words += "your contacts" }
        if (missing.isEmpty()) return true
        speaker.say(
            "I need permission to use ${words.joinToString(" and ")}. A box will appear on the screen. Please allow it, then ask me again.",
            Priority.Interrupt,
        )
        permissionRequests.request(missing)
        return false
    }

    /** Ask a yes/no question out loud and listen for the answer. Anything but a clear yes means no. */
    private fun askConfirm(prompt: String, onYes: () -> Unit) {
        pendingReply = { reply ->
            if (EmergencyIntents.isYes(reply)) onYes() else speaker.say("Okay, I won't.", Priority.Interrupt)
        }
        openTurnWith(prompt)
    }

    private fun sos() {
        if (!ensure(location = true, sms = true)) return
        viewModelScope.launch {
            val contacts = settings.settings.first().emergencyContacts
            if (contacts.isEmpty()) {
                speaker.say(Emergency.say(ai.visionmirror.emergency.ShareResult.NoContacts), Priority.Interrupt)
                return@launch
            }
            sosJob?.cancel()
            sosJob = launch {
                // A spoken countdown she can cancel. Nothing is ever sent the instant she says it.
                val names = contacts.joinToString(" and ") { it.name }
                speaker.say("I will send your location to $names in five seconds. Say cancel to stop.", Priority.Interrupt)
                delay(SOS_COUNTDOWN_MS)
                val result = emergency.share(contacts, "I need help.")
                val sent = result is ai.visionmirror.emergency.ShareResult.Sent && result.names.isNotEmpty()
                speaker.say(Emergency.say(result) + if (sent) " Say call one one two if you want the phone to dial for help." else "", Priority.Interrupt)
            }
        }
    }

    private fun cancelSos() {
        sosJob?.cancel()
        sosJob = null
        speaker.say("Cancelled. I did not send anything.", Priority.Interrupt)
    }

    private fun share(name: String?) {
        if (!ensure(location = true, sms = true)) return
        viewModelScope.launch {
            val saved = settings.settings.first().emergencyContacts
            val recipients = if (name == null) {
                saved
            } else {
                saved.filter { it.name.contains(name, ignoreCase = true) }.ifEmpty {
                    if (!ensure(contacts = true)) return@launch
                    contactBook.find(name).take(1)
                }
            }
            if (recipients.isEmpty()) {
                speaker.say(
                    if (name == null) Emergency.say(ai.visionmirror.emergency.ShareResult.NoContacts) else "I couldn't find $name in your contacts.",
                    Priority.Interrupt,
                )
                return@launch
            }
            val who = recipients.joinToString(" and ") { it.name }
            askConfirm("Send your location to $who? Say yes to send.") {
                viewModelScope.launch {
                    speaker.say("Sending.", Priority.Interrupt, local = true)
                    speaker.say(Emergency.say(emergency.share(recipients, "This is my location.")), Priority.Interrupt)
                }
            }
        }
    }

    // ---- "set my emergency contact": the assistant asks for the name and the number, and she answers by voice -----------

    private val _contactDraft = MutableStateFlow<ai.visionmirror.emergency.ContactDraft?>(null)

    /** What is being entered right now; the Settings page shows it in its fields as it is said. */
    val contactDraft: StateFlow<ai.visionmirror.emergency.ContactDraft?> = _contactDraft

    private fun startContactDialog() {
        _contactDraft.value = ai.visionmirror.emergency.ContactDraft(step = "name")
        _actions.tryEmit(AssistantAction.OpenSettings) // the Emergency contact page fills in as she speaks
        askContactName("Let's set your emergency contact. Who should it be? Say their name.")
    }

    private var contactSilences = 0

    private fun contactTurn(prompt: String, onReply: (String) -> Unit): Unit {
        pendingReply = { reply ->
            // Silence is not the end of the conversation: the step asks again, and only three silent turns in a row stop it.
            if (reply.isBlank()) contactSilences++ else contactSilences = 0
            if (contactSilences >= 3) {
                contactSilences = 0
                abortContact("I stopped waiting. Say set my emergency contact when you want to carry on.")
            } else {
                onReply(reply)
            }
        }
        openTurnWith(prompt)
    }

    private fun abortContact(said: String = "Okay. I did not change your emergency contact.") {
        _contactDraft.value = null
        speaker.say(said, Priority.Interrupt)
    }

    private fun askContactName(prompt: String): Unit = contactTurn(prompt) { reply ->
        val text = reply.trim()
        when {
            text.isBlank() -> askContactName("I didn't catch the name. Say it again, or spell it letter by letter.")
            ai.visionmirror.emergency.SpokenInput.isCancel(text) -> abortContact()
            else -> {
                val name = ai.visionmirror.emergency.SpokenInput.spelled(text) ?: ai.visionmirror.emergency.SpokenInput.cleanName(text)
                if (name.isBlank()) askContactName("I didn't catch the name. Say it again, or spell it letter by letter.")
                else confirmContactName(name)
            }
        }
    }

    private fun confirmContactName(name: String) {
        _contactDraft.update { it?.copy(name = name, step = "name") }
        contactTurn("I heard $name. Say yes if that is right, or spell it for me, letter by letter.") { reply ->
            val spelling = ai.visionmirror.emergency.SpokenInput.spelled(reply)
            when {
                reply.isBlank() -> confirmContactName(name)
                ai.visionmirror.emergency.SpokenInput.isCancel(reply) -> abortContact()
                spelling != null -> confirmContactName(spelling)
                ai.visionmirror.emergency.SpokenInput.isNo(reply) ->
                    askContactName("Okay. Spell the name for me, one letter at a time.")
                ai.visionmirror.emergency.SpokenInput.isYes(reply) -> askContactNumber("Now the phone number. Take your time: say a few digits, pause, and go on. Say done when you have finished.")
                else -> {
                    // Neither yes nor spelling: take it as the name said again.
                    val again = ai.visionmirror.emergency.SpokenInput.cleanName(reply)
                    if (again.isBlank()) confirmContactName(name) else confirmContactName(again)
                }
            }
        }
    }

    /**
     * The number is taken in pieces: people pause between groups of digits, so each piece is added to what was heard before, shown on
     * the page, and answered with "Go on". It is finished when it looks complete (11 digits, or +234 and 12) or she says "done".
     */
    private fun askContactNumber(prompt: String, soFar: String = "") {
        _contactDraft.update { it?.copy(number = soFar, step = "number") }
        contactTurn(prompt) { reply -> onNumberPiece(reply, soFar) }
    }

    private fun onNumberPiece(reply: String, soFar: String) {
        val spoken = ai.visionmirror.emergency.SpokenInput
        if (spoken.isCancel(reply)) return abortContact()
        if (spoken.wantsNameChange(reply)) return askContactName("Okay. Say the name, or spell it.")
        val piece = spoken.digits(reply)
        val all = if (piece.startsWith("+") && soFar.isNotEmpty()) soFar + piece.drop(1) else soFar + piece
        val count = all.count { it.isDigit() }
        val complete = if (all.startsWith("+")) count >= 12 else count >= 11
        val done = spoken.saysDone(reply)
        _contactDraft.update { it?.copy(number = all, step = "number") }
        when {
            count >= 7 && (complete || done || (piece.isEmpty() && reply.isBlank())) -> confirmContactNumber(all)
            piece.isEmpty() && soFar.isEmpty() ->
                askContactNumber("I didn't catch any digits. Say the whole number, a few digits at a time. Say done when you have finished.")
            piece.isEmpty() -> contactTurn(if (reply.isBlank()) "Still listening. Go on, or say done." else "I didn't catch digits there. Go on with the number.") { r -> onNumberPiece(r, all) }
            done -> contactTurn("That is only $count digits so far. Go on with the rest.") { r -> onNumberPiece(r, all) }
            else -> contactTurn("Go on.") { r -> onNumberPiece(r, all) }
        }
    }

    private fun confirmContactNumber(number: String) {
        _contactDraft.update { it?.copy(number = number, step = "number") }
        contactTurn("I heard ${ai.visionmirror.emergency.SpokenInput.spaced(number)}. Say yes if that is right, or say the number again.") { reply ->
            val again = ai.visionmirror.emergency.SpokenInput.digits(reply)
            when {
                ai.visionmirror.emergency.SpokenInput.isCancel(reply) -> abortContact()
                again.count { it.isDigit() } >= 7 -> confirmContactNumber(again) // she simply said the number again
                ai.visionmirror.emergency.SpokenInput.isNo(reply) -> askContactNumber("Okay. Say the number again, a few digits at a time.")
                ai.visionmirror.emergency.SpokenInput.isYes(reply) -> askContactSave()
                else -> confirmContactNumber(number)
            }
        }
    }

    private fun askContactSave() {
        val draft = _contactDraft.value ?: return
        _contactDraft.update { it?.copy(step = "save") }
        contactTurn(
            "Do you want to save ${draft.name}, ${ai.visionmirror.emergency.SpokenInput.spaced(draft.number)}, as your emergency contact? " +
                "Say save, or cancel. You can also say change the name, or change the number.",
        ) { reply ->
            when {
                ai.visionmirror.emergency.SpokenInput.wantsNameChange(reply) -> askContactName("Okay. Say the name, or spell it.")
                ai.visionmirror.emergency.SpokenInput.wantsNumberChange(reply) -> askContactNumber("Okay. Say the number, one digit at a time.")
                ai.visionmirror.emergency.SpokenInput.isCancel(reply) || ai.visionmirror.emergency.SpokenInput.isNo(reply) -> abortContact()
                ai.visionmirror.emergency.SpokenInput.isSave(reply) -> {
                    val contact = ai.visionmirror.data.settings.EmergencyContact(draft.name, draft.number)
                    viewModelScope.launch {
                        settings.update { s -> s.copy(emergencyContacts = (listOf(contact) + s.emergencyContacts).distinctBy { it.number }.take(3)) }
                        _contactDraft.value = null
                        earcons.play(Earcon.Ready)
                        speaker.say("Saved. ${draft.name} is now your emergency contact.", Priority.Interrupt)
                    }
                }
                else -> askContactSave()
            }
        }
    }

    private fun setContact(name: String) {
        if (!ensure(contacts = true)) return
        viewModelScope.launch {
            val found = contactBook.find(name).firstOrNull()
            if (found == null) {
                speaker.say("I couldn't find $name in your contacts. Check the spelling, or say a different name.", Priority.Interrupt)
                return@launch
            }
            askConfirm("I found ${found.name}, number ending ${Emergency.endingIn(found.number)}. Say yes to save as your emergency contact.") {
                viewModelScope.launch {
                    settings.update { s -> s.copy(emergencyContacts = (s.emergencyContacts + found).distinctBy { it.number }.takeLast(3)) }
                    speaker.say("Saved. ${found.name} is now your emergency contact.", Priority.Interrupt)
                }
            }
        }
    }

    private fun applySetting(change: SettingChange) {
        when (change) {
            is SettingChange.Set -> viewModelScope.launch {
                val beforeSettings = settings.settings.first()
                val before = beforeSettings.detailLevel
                settings.update(change.change)
                val afterSettings = settings.settings.first()
                val after = afterSettings.detailLevel
                // The new voice is applied a moment after the setting saves; wait for it so she says hello as herself.
                if (afterSettings.persona != beforeSettings.persona) delay(250)
                earcons.play(Earcon.Ready)
                speaker.say(change.say, Priority.Interrupt)
                // "Make it more detailed" while a result is open: describe the same photo again at that level.
                if (after != before && (_state.value.phase == Phase.Result || _state.value.phase == Phase.Unusable)) changeDetail(after)
            }
            is SettingChange.Volume -> speaker.say(device.volume(change.steps), Priority.Interrupt)
            SettingChange.VolumeMax -> speaker.say(device.volumeMax(), Priority.Interrupt)
            SettingChange.NextVoice -> speaker.say(device.nextVoice(), Priority.Interrupt)
            SettingChange.Help -> speaker.say(VoiceSettings.HELP, Priority.Interrupt)
        }
    }

    // ---- hands-free ("access granted") and screen commands ----------------------------------------

    /** Which screen is open ("home", "mirror", "look/read", "result"...). */
    fun onScreen(route: String?) {
        screen = route
    }

    private fun applyHandsFree(change: HandsFree.Change) {
        val on = change == HandsFree.Change.On
        silentCycles = 0
        viewModelScope.launch {
            settings.update { it.copy(handsFree = on) }
            earcons.play(Earcon.Ready)
            speaker.say(if (on) HandsFree.ON_SAY else HandsFree.OFF_SAY, Priority.Interrupt)
        }
    }

    private fun routeAction(action: AssistantAction) {
        when (action) {
            // On the mirror it takes the photo; on the read, look and guide screens it is not meant; anywhere else it opens the mirror and takes it.
            AssistantAction.TakePhoto -> if (screen == "mirror" || onCameraScreen()) screenCommands.send(ScreenCommand.TakePhoto) else snapSelfie()
            AssistantAction.FlipCamera -> screenCommands.send(ScreenCommand.FlipCamera)
            AssistantAction.LookAgain -> screenCommands.send(ScreenCommand.LookAgain)
            // In Guide mode "what's around me" describes the scene right now instead of leaving the screen.
            AssistantAction.OpenLook -> if (screen == "guide") screenCommands.send(ScreenCommand.DescribeNow) else _actions.tryEmit(action)
            AssistantAction.SnapSelfie -> snapSelfie()
            AssistantAction.UseFrontCamera -> useCamera(CameraFacing.Front)
            AssistantAction.UseBackCamera -> useCamera(CameraFacing.Back)
            else -> _actions.tryEmit(action)
        }
    }

    /** "Take a picture of me": the photo is taken at once, from whatever screen she is on. */
    private fun snapSelfie() {
        viewModelScope.launch { settings.update { it.copy(cameraFacing = CameraFacing.Front) } }
        if (screen == "mirror") {
            screenCommands.send(ScreenCommand.TakePhoto)
        } else {
            screenCommands.later(ScreenCommand.TakePhoto) // the mirror takes it as soon as its camera is ready
            _actions.tryEmit(AssistantAction.OpenMirror)
        }
    }

    /** "Switch to the front camera" really switches it (the mirror follows the saved setting), or opens what has it. */
    private fun useCamera(facing: CameraFacing) {
        viewModelScope.launch { settings.update { it.copy(cameraFacing = facing) } }
        val front = facing == CameraFacing.Front
        when {
            screen == "mirror" -> speaker.say(if (front) "Using the front camera." else "Using the back camera.", Priority.Interrupt, local = true)
            front -> {
                speaker.say("Opening the front camera.", Priority.Interrupt, local = true)
                _actions.tryEmit(AssistantAction.OpenMirror)
            }
            screen == "guide" || screen?.startsWith("look") == true ->
                speaker.say("Reading and guiding already use the back camera.", Priority.Interrupt, local = true)
            else -> {
                speaker.say("Opening the back camera.", Priority.Interrupt, local = true)
                _actions.tryEmit(AssistantAction.OpenLook)
            }
        }
    }

    private fun SpeechManager.Event.eventId(): String = when (this) {
        is SpeechManager.Event.Started -> id
        is SpeechManager.Event.Done -> id
        is SpeechManager.Event.Failed -> id
    }

    /**
     * Hands-free: once she has finished speaking, open the microphone for the next request with no need to say her
     * name. Only on screens where she is just talking (not while the camera is scanning), and never while a request
     * is still being worked on.
     */
    private fun maybeHandsFree() {
        if (!handsFree) return
        viewModelScope.launch {
            delay(900) // let a following sentence start, and the room go quiet, before deciding she is finished
            val quiet = speaker.state.value.utteranceId == null
            val roomToListen = screen in HANDS_FREE_SCREENS
            val idle = !assistantTurn && listenMode == ListenMode.None && _state.value.ask == AskPhase.Idle &&
                _state.value.phase != Phase.Analysing
            if (inBackground || wake.asleep.value || !handsFree || !quiet || !roomToListen || !idle || !permissions.hasMic()) return@launch
            assistantTurn = true
            assistantAttempts = 0
            assistantStartedAt = clock()
            wake.hold(true)
            listenMode = ListenMode.Assistant
            _state.update { it.copy(ask = AskPhase.Listening, partialQuestion = "", banner = null) }
            earcons.play(Earcon.ListeningOn)
            voice.start(language.recognitionTag())
        }
    }

    /** Tap anywhere on a camera screen: hold the scan or countdown, then listen, so she has time to speak. */
    fun talkFromCamera() {
        screenCommands.send(ScreenCommand.Hold)
        toggleListening()
    }

    /** The orb on Home: tap to talk to Adanna, tap again to stop. */
    fun toggleListening() {
        if (listenMode == ListenMode.Question || listenMode == ListenMode.Assistant) {
            cancelAsking()
        } else {
            handleWake("")
        }
    }

    private fun submitQuestion(question: String) {
        val id = sessionId
        if (id == null) {
            _state.update { it.copy(ask = AskPhase.Idle) }
            return
        }
        _state.update { it.copy(ask = AskPhase.Thinking, partialQuestion = question) }
        speaker.say("Let me look.", Priority.Interrupt)
        viewModelScope.launch {
            askWithRetry(id, question)
                .onSuccess { a ->
                    _state.update {
                        it.copy(
                            ask = AskPhase.Idle, partialQuestion = "", displayText = a.answerText,
                            conversation = it.conversation + QA(question, a.answerText), banner = null,
                        )
                    }
                    earcons.play(Earcon.Ready)
                    lastDescriptionSpeechId = speaker.say(a.spokenText, Priority.Interrupt)
                }
                .onFailure { t ->
                    val error = t as? AppError ?: AppError.Unexpected(t)
                    _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "", banner = error.spokenText) }
                    earcons.play(Earcon.Error)
                    haptics.error()
                    speaker.say(error.spokenText, Priority.Interrupt)
                    if (error is AppError.SessionExpired) {
                        sessionId = null
                        _events.send(SessionEvent.NeedNewPhoto)
                    }
                }
        }
    }

    /** A dropped connection should not cost her the question: try again a few times, quietly, before giving up. */
    private suspend fun askWithRetry(id: String, question: String): Result<AskResponse> {
        var attempt = 0
        while (true) {
            val result = repo.ask(id, question, language.apiTag)
            val error = result.exceptionOrNull()
            val transient = error is AppError.NetworkTimeout || (error is AppError.Offline && connectivity.online.first())
            if (!transient || attempt >= MAX_NETWORK_RETRIES) return result
            if (attempt == 0) speaker.say("Your connection is slow. I'll keep trying.", Priority.Interrupt)
            delay(1_000L shl attempt)
            attempt++
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** Retake: forget this photo and conversation, and go back to the mirror. */
    fun retake() {
        clear()
        viewModelScope.launch { _events.send(SessionEvent.Retake) }
    }

    /** Settings > Delete session data, or leaving the result: nothing of this photo remains. */
    fun clear() {
        describeJob?.cancel()
        voice.cancel()
        speaker.stop()
        listenMode = ListenMode.None
        _micLevel.value = 0f
        endRemoteSession()
        jpeg = null
        val detail = _state.value.detail
        _state.value = SessionUiState(detail = detail)
    }

    private fun endRemoteSession() {
        val id = sessionId ?: return
        sessionId = null
        // appScope, not viewModelScope: the delete must survive this screen going away.
        appScope.launch { repo.deleteSession(id) }
    }

    override fun onCleared() {
        endRemoteSession()
        voice.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_RATE_RETRIES = 2
        const val MIC_FALLBACK_MS = 2_500L
        const val MIC_SETTLE_MS = 300L
        const val SOS_COUNTDOWN_MS = 8_000L
        const val HANDS_FREE_QUIET_CYCLES = 3
        val HANDS_FREE_SCREENS = setOf("home", "result", "settings")
        /** Keep listening this long, in up to this many quiet restarts, before saying she was not heard. */
        const val ASSISTANT_PATIENCE_MS = 6_000L
        const val CONTACT_PATIENCE_MS = 12_000L // spelling a name or reading out digits takes longer
        const val ASSISTANT_RETRIES = 40
        const val ASSISTANT_RETRY_GAP_MS = 350L
        const val MAX_NETWORK_RETRIES = 4
    }
}
