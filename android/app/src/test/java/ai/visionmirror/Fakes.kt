package ai.visionmirror

import ai.visionmirror.audio.Earcon
import ai.visionmirror.audio.Earcons
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.VoiceEvent
import ai.visionmirror.audio.VoiceInput
import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.api.AskResponse
import ai.visionmirror.data.api.ColourHarmony
import ai.visionmirror.data.api.DescribeResponse
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.api.ImageQuality
import ai.visionmirror.data.net.Connectivity
import ai.visionmirror.data.net.PermissionChecker
import ai.visionmirror.data.repo.MirrorRepository
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    private val ids = mutableListOf<String>()
    var stops = 0
    var pauses = 0
    override val state = MutableStateFlow(SpeechManager.State())
    private val _events = MutableSharedFlow<SpeechManager.Event>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechManager.Event> = _events

    override fun speak(text: String, priority: SpeechManager.Priority, id: String): String {
        spoken += text
        ids += id
        return id
    }

    override fun stop() { stops++ }
    override fun pause() { pauses++ }
    override fun resume() = Unit
    fun finish(id: String) { _events.tryEmit(SpeechManager.Event.Done(id)) }

    /** Pretend the most recent utterance has just finished being spoken. */
    fun finishLast() = finish(ids.last())
}

class FakeEarcons : Earcons {
    val played = mutableListOf<Earcon>()
    override fun play(earcon: Earcon) { played += earcon }
}

class FakeVoice : VoiceInput {
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<VoiceEvent> = _events
    override var isAvailable = true
    var starts = 0
    var finishes = 0
    var cancels = 0
    override fun start(language: String?) { starts++ }
    override fun finish() { finishes++ }
    override fun cancel() { cancels++ }
    fun emit(e: VoiceEvent) { _events.tryEmit(e) }
}

class FakeConnectivity(initial: Boolean = true) : Connectivity {
    val flow = MutableStateFlow(initial)
    override val online: Flow<Boolean> = flow
}

class FakeSettings(initial: Settings = Settings()) : SettingsStore {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<Settings> = flow
    override suspend fun update(transform: (Settings) -> Settings) { flow.value = transform(flow.value) }
}

class FakePermissions(var camera: Boolean = true, var mic: Boolean = true) : PermissionChecker {
    override fun hasCamera() = camera
    override fun hasMic() = mic
}

class FakeRepo : MirrorRepository {
    val describeResults = ArrayDeque<Result<DescribeResponse>>()
    val askResults = ArrayDeque<Result<AskResponse>>()
    val describeDetails = mutableListOf<DetailLevel>()
    val questions = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun describe(jpeg: ByteArray, detail: DetailLevel, language: String): Result<DescribeResponse> {
        describeDetails += detail
        return describeResults.removeFirstOrNull() ?: Result.failure(AppError.Unexpected())
    }

    override suspend fun ask(sessionId: String, question: String, language: String): Result<AskResponse> {
        questions += question
        return askResults.removeFirstOrNull() ?: Result.failure(AppError.Unexpected())
    }

    override suspend fun deleteSession(sessionId: String) { deleted += sessionId }
}

fun described(session: String? = "s1", text: String = "You look good.", usable: Boolean = true) = DescribeResponse(
    sessionId = session,
    imageQuality = ImageQuality(usable, advice = if (usable) null else "Try facing a window."),
    summary = "ok",
    colourHarmony = ColourHarmony(),
    spokenText = text,
    confidence = 0.8f,
)
