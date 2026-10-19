package ai.visionmirror.assistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

private fun normalise(utterance: String): String =
    utterance.lowercase().replace("'", "").replace("’", "").replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")

/**
 * Words that stop her talking. Only checked against SHORT utterances (three words or fewer), because while she is
 * speaking the microphone also hears her own voice, and a long transcript is her, not the user.
 */
object StopWords {
    private val words = setOf("stop", "quiet", "enough", "silence", "hush", "cancel", "pause", "wait")

    fun matches(utterance: String): Boolean {
        val text = normalise(utterance)
        val parts = text.split(' ')
        if (text.isEmpty() || parts.size > 4) return false
        if (parts.size <= 4 && VoiceCatalog.phrases("cam.pause", "wake.stop").any { " $it " in " $text " }) return true
        if (parts.size > 3) return false
        return text == "shut up" || text == "hold on" || text == "hang on" || parts.any { it in words }
    }
}

/** "Go on", "continue", "I'm ready": picks things up again after a pause. Short phrases only, and never while she is talking. */
object ResumeWords {
    private val phrases = setOf("resume", "continue", "go on", "carry on", "keep going", "ready", "im ready", "i am ready", "okay go", "ok go")

    fun matches(utterance: String): Boolean {
        val text = normalise(utterance)
        return text.isNotEmpty() && text.split(' ').size <= 3 && (text in phrases || text in VoiceCatalog.phrases("cam.resume"))
    }
}

/**
 * "Access granted": she takes over the app by voice. While it is on she keeps listening after every answer, so the
 * user never has to touch the screen or say her name again, until they say "that's all".
 */
object HandsFree {
    enum class Change { On, Off }

    private val off = listOf(
        "hands free off", "turn off hands free", "release access", "revoke access", "take back control", "stand down",
        "thats all", "that is all", "goodbye", "good bye", "bye adanna", "stop listening", "im done", "i am done",
    )
    private val on = listOf(
        "access", "access granted", "grant access", "i grant you access", "you have access", "full access",
        "take control", "take over", "hands free", "hands free on", "go hands free", "turn on hands free",
    )

    fun parse(utterance: String): Change? {
        val text = normalise(utterance)
        if (text.isEmpty() || text.split(' ').size > 7) return null
        val padded = " $text "
        if (off.any { " $it " in padded }) return Change.Off
        if (on.any { " $it " in padded }) return Change.On
        return null
    }

    const val ON_SAY =
        "Access granted. I'm in control now. Just tell me what to do and I'll keep listening, so you don't need to touch the screen. " +
            "Say that's all when you're done."
    const val OFF_SAY = "Okay. I've handed control back. Say my name whenever you need me."
}

/** The time and the date come from the phone, not the internet. */
object ClockAnswer {
    fun answer(utterance: String, now: ZonedDateTime): String? {
        val text = normalise(utterance)
        if (text.isEmpty() || text.split(' ').size > 8) return null
        fun has(vararg p: String) = p.any { " $it " in " $text " }
        val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(now)
        val date = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)
        return when {
            has("what time is it", "what is the time", "whats the time", "tell me the time", "the time") -> "It is $time."
            has("what day is it", "what is the date", "whats the date", "todays date", "what date is it", "what is today", "the date") ->
                "Today is $date."
            else -> null
        }
    }
}

enum class ScreenCommand { TakePhoto, FlipCamera, LookAgain, Pause, Resume, Hold }

/** Voice commands for the screen that is open (take the photo now, flip the camera). The open screen acts on them. */
@Singleton
class ScreenCommands @Inject constructor() {
    private val _flow = MutableSharedFlow<ScreenCommand>(extraBufferCapacity = 4)
    val flow: SharedFlow<ScreenCommand> = _flow.asSharedFlow()
    fun send(command: ScreenCommand) { _flow.tryEmit(command) }

    @Volatile private var pending: ScreenCommand? = null

    /** For a screen that is not open yet ("take a picture of me" from Home): it does this the moment it opens. */
    fun later(command: ScreenCommand) { pending = command }

    fun takePending(): ScreenCommand? = pending.also { pending = null }
}
