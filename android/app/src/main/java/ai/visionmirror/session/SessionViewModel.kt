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
import ai.visionmirror.audio.WakeSource
import ai.visionmirror.audio.WakeWord
import ai.visionmirror.assistant.AssistantAction
import ai.visionmirror.assistant.AssistantIntents
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
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

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
    private var assistantStartedAt = 0L

    init {
        viewModelScope.launch {
            settings.settings.collectLatest { s ->
                voiceCommandsEnabled = s.voiceCommands
                language = s.language
                if (_state.value.phase == Phase.Idle) _state.update { it.copy(detail = s.detailLevel) }
            }
        }
        viewModelScope.launch { voice.events.collect(::onVoiceEvent) }
        viewModelScope.launch { wake.wakes.collect(::onWake) }
        // The microphone opens the moment "Listening." has been said.
        viewModelScope.launch {
            speaker.events.collect { e ->
                val id = pendingMic ?: return@collect
                if ((e is SpeechManager.Event.Done && e.id == id) || (e is SpeechManager.Event.Failed && e.id == id)) openAssistantMic()
            }
        }
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
        _state.update { it.copy(ask = AskPhase.Listening, partialQuestion = "", banner = null) }
        earcons.play(Earcon.ListeningOn)
        haptics.click()
        voice.start(language.recognitionTag())
    }

    /** Hold-to-talk release: deliver what was heard. */
    fun finishAsking() {
        if (listenMode == ListenMode.Question) voice.finish()
    }

    fun cancelAsking() {
        pendingMic = null
        endAssistantTurn()
        voice.cancel()
        listenMode = ListenMode.None
        _micLevel.value = 0f
        _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
    }

    private fun maybeListenForCommand() {
        if (!voiceCommandsEnabled || !permissions.hasMic()) return
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
                WakeWord.matches(text)?.let { handleWake(it.request); return }
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
        // She said nothing yet (or the recogniser lost it). Quietly listen again instead of giving up straight away.
        if (mode == ListenMode.Assistant && reason == VoiceFailure.NoSpeech && assistantAttempts < ASSISTANT_RETRIES &&
            System.currentTimeMillis() - assistantStartedAt < ASSISTANT_PATIENCE_MS
        ) {
            assistantAttempts++
            listenMode = ListenMode.Assistant
            voice.start(language.recognitionTag())
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
            val line = if (reason == VoiceFailure.NoSpeech) "I didn't catch that. Say my name and try again." else "I couldn't hear you just now. Say my name and try again."
            _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "", banner = if (code != 0) "$line (speech error $code)" else line) }
            earcons.play(Earcon.ListeningOff)
            speaker.say(line, Priority.Interrupt, local = true)
        }
        // A failed command window is silent on purpose: she did not ask for anything.
    }

    // ---- assistant: "Adanna ..." -------------------------------------------------------------

    /** The wake word was heard anywhere in the app. [request] is what she said after it, if anything. */
    private fun onWake(request: String) {
        if (listenMode == ListenMode.Question || listenMode == ListenMode.Assistant) return
        haptics.click()
        handleWake(request)
    }

    private fun endAssistantTurn() {
        assistantTurn = false
        wake.hold(false)
    }

    private fun handleWake(request: String) {
        if (request.isNotBlank()) {
            speaker.stop()
            handleRequest(request)
            return
        }
        if (!permissions.hasMic()) {
            speaker.say("I need the microphone to hear you. You can allow it in your phone's settings.", Priority.Interrupt, local = true)
            return
        }
        speaker.stop()
        assistantTurn = true
        assistantAttempts = 0
        assistantStartedAt = System.currentTimeMillis()
        wake.hold(true) // nothing else may use the microphone until her request is in
        listenMode = ListenMode.Assistant
        _state.update { it.copy(ask = AskPhase.Listening, partialQuestion = "", banner = null) }
        earcons.play(Earcon.ListeningOn)
        // "Listening." in the phone's own voice (instant), then the microphone opens when she has finished saying it,
        // so the recogniser never hears me. If speech is broken, the timer opens the microphone anyway.
        val id = speaker.say("Listening.", Priority.Interrupt, local = true)
        pendingMic = id
        viewModelScope.launch {
            delay(MIC_FALLBACK_MS)
            if (pendingMic == id) openAssistantMic()
        }
    }

    private fun openAssistantMic() {
        pendingMic = null
        if (listenMode != ListenMode.Assistant) { endAssistantTurn(); return }
        // A beat of quiet after "Listening." so the end of my own voice is not what the microphone hears first.
        viewModelScope.launch {
            delay(MIC_SETTLE_MS)
            if (listenMode == ListenMode.Assistant) {
                assistantStartedAt = System.currentTimeMillis()
                voice.start(language.recognitionTag())
            }
        }
    }

    /** What she asked for: a control word, a place to go, or (when there is a photo) a question about it. */
    private fun handleRequest(raw: String) {
        val text = WakeWord.strip(raw)
        val hasPhoto = sessionId != null
        // Settings first: "stop listening for my name" is a setting, not the command "stop".
        VoiceSettings.parse(text)?.let { applySetting(it); return }
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
        AssistantIntents.parse(text, hasPhoto)?.let { _actions.tryEmit(it); return }
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

    private fun applySetting(change: SettingChange) {
        when (change) {
            is SettingChange.Set -> viewModelScope.launch {
                settings.update(change.change)
                earcons.play(Earcon.Ready)
                speaker.say(change.say, Priority.Interrupt)
            }
            is SettingChange.Volume -> speaker.say(device.volume(change.steps), Priority.Interrupt)
            SettingChange.VolumeMax -> speaker.say(device.volumeMax(), Priority.Interrupt)
            SettingChange.NextVoice -> speaker.say(device.nextVoice(), Priority.Interrupt)
            SettingChange.Help -> speaker.say(VoiceSettings.HELP, Priority.Interrupt)
        }
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
        /** Keep listening this long, in up to this many quiet restarts, before saying she was not heard. */
        const val ASSISTANT_PATIENCE_MS = 7_000L
        const val ASSISTANT_RETRIES = 2
        const val MAX_NETWORK_RETRIES = 4
    }
}
