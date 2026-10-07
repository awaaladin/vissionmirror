package ai.visionmirror.session

import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.VoiceCommand
import ai.visionmirror.audio.VoiceCommands
import ai.visionmirror.audio.VoiceEvent
import ai.visionmirror.audio.VoiceFailure
import ai.visionmirror.audio.VoiceInput
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.AppError
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
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
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state

    /** Mic level 0..1 for the Quartz ripples. A separate flow so ~30 updates/s never recompose the screen. */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    private val _events = Channel<SessionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private enum class ListenMode { None, Question, Command }

    private var listenMode = ListenMode.None
    private var jpeg: ByteArray? = null
    private var sessionId: String? = null
    private var describeJob: Job? = null
    private var lastDescriptionSpeechId: String? = null
    private var voiceCommandsEnabled = true

    init {
        viewModelScope.launch {
            settings.settings.collectLatest { s ->
                voiceCommandsEnabled = s.voiceCommands
                if (_state.value.phase == Phase.Idle) _state.update { it.copy(detail = s.detailLevel) }
            }
        }
        viewModelScope.launch { voice.events.collect(::onVoiceEvent) }
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

    fun submitPhoto(jpegBytes: ByteArray, preview: Bitmap?) {
        endRemoteSession()
        jpeg = jpegBytes
        _state.update {
            it.copy(
                phase = Phase.Analysing, photo = preview, result = null, displayText = "",
                conversation = emptyList(), ask = AskPhase.Idle, banner = null, refreshing = false,
            )
        }
        speaker.say("One moment. I'm looking at you.", Priority.Interrupt)
        startDescribe()
    }

    private fun startDescribe(attempt: Int = 0) {
        val bytes = jpeg ?: return
        val detail = _state.value.detail
        describeJob?.cancel()
        describeJob = viewModelScope.launch {
            repo.describe(bytes, detail, deviceLanguage())
                .onSuccess(::onDescribed)
                .onFailure { onDescribeFailed(it, attempt) }
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

    private suspend fun onDescribeFailed(t: Throwable, attempt: Int) {
        val error = t as? AppError ?: AppError.Unexpected(t)
        val hadResult = _state.value.result != null // refreshing detail level: keep what she already has
        when {
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
                startDescribe(attempt + 1)
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
        voice.start(deviceLanguage())
    }

    /** Hold-to-talk release: deliver what was heard. */
    fun finishAsking() {
        if (listenMode == ListenMode.Question) voice.finish()
    }

    fun cancelAsking() {
        voice.cancel()
        listenMode = ListenMode.None
        _micLevel.value = 0f
        _state.update { it.copy(ask = AskPhase.Idle, partialQuestion = "") }
    }

    private fun maybeListenForCommand() {
        if (!voiceCommandsEnabled || !permissions.hasMic()) return
        if (_state.value.phase != Phase.Result || _state.value.ask != AskPhase.Idle) return
        listenMode = ListenMode.Command
        voice.start(deviceLanguage())
    }

    private fun onVoiceEvent(e: VoiceEvent) {
        when (e) {
            VoiceEvent.Ready -> if (listenMode == ListenMode.Command) earcons.play(Earcon.ListeningOn)
            is VoiceEvent.Level -> _micLevel.value = e.level
            is VoiceEvent.Partial -> if (listenMode == ListenMode.Question) _state.update { it.copy(partialQuestion = e.text) }
            is VoiceEvent.Final -> onHeard(e.text)
            is VoiceEvent.Failed -> onVoiceFailed(e.reason)
        }
    }

    private fun onHeard(text: String) {
        val mode = listenMode
        listenMode = ListenMode.None
        _micLevel.value = 0f
        when (mode) {
            ListenMode.Question -> {
                earcons.play(Earcon.ListeningOff)
                submitQuestion(text)
            }
            ListenMode.Command -> {
                earcons.play(Earcon.ListeningOff)
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
            ListenMode.None -> Unit
        }
    }

    private fun onVoiceFailed(reason: VoiceFailure) {
        val mode = listenMode
        listenMode = ListenMode.None
        _micLevel.value = 0f
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
        // A failed command window is silent on purpose: she did not ask for anything.
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
            repo.ask(id, question, deviceLanguage())
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

    private fun deviceLanguage(): String {
        val l = Locale.getDefault()
        // Matches the API's pattern: "en" or "pt-BR", never "zh-Hans-CN".
        return if (l.country.isNotEmpty()) "${l.language}-${l.country}" else l.language.ifEmpty { "en" }
    }

    private companion object {
        const val MAX_RATE_RETRIES = 2
    }
}
