package ai.visionmirror.settings

import ai.visionmirror.app.Spoken
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.design.tokens.FontChoice
import ai.visionmirror.design.tokens.ThemeMode
import android.speech.tts.Voice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Speech rates offered as words, because "1.25" means nothing to a screen-reader user. */
enum class SpeechRate(val label: String, val value: Float) {
    Slow("Slow", 0.75f), Normal("Normal", 1.0f), Fast("Fast", 1.3f), Faster("Faster", 1.6f);

    companion object {
        fun nearest(v: Float): SpeechRate = entries.minBy { kotlin.math.abs(it.value - v) }
    }
}

/** Text size offered as words. It multiplies the phone's own font size setting. */
enum class TextSize(val label: String, val scale: Float) {
    Small("Small", 0.85f),
    Normal("Normal", 1.0f),
    Large("Large", 1.25f),
    ExtraLarge("Extra large", 1.5f),
    Huge("Huge", 1.8f);

    companion object {
        fun nearest(v: Float): TextSize = entries.minBy { kotlin.math.abs(it.scale - v) }
    }
}

/** One installed voice, with a label a person can understand (the engine's own names are codes). */
data class VoiceOption(val name: String, val label: String)

/** Android voice names look like "en-gb-x-gbb-local". Make them readable and number them per language. */
internal fun voiceOptions(voices: List<Voice>): List<VoiceOption> =
    voices.mapIndexed { i, v ->
        val place = v.locale.displayCountry.takeIf { it.isNotBlank() }
        val language = v.locale.displayLanguage.takeIf { it.isNotBlank() } ?: "Voice"
        VoiceOption(v.name, "Voice ${i + 1}: $language" + (place?.let { " ($it)" } ?: ""))
    }

/** Seven taps on the version line turn on Developer tools, like the Android system setting. */
const val DEVELOPER_TAPS = 7

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val speech: SpeechManager,
) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private var versionTaps = 0

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { store.update(transform) }
    }

    fun setSpeechRate(rate: SpeechRate) {
        update { it.copy(speechRate = rate.value) }
        speech.setRate(rate.value)
        speech.say("This is how fast I'll speak.", Priority.Interrupt)
    }

    fun setDetail(level: DetailLevel) = update { it.copy(detailLevel = level) }
    fun setHaptics(on: Boolean) = update { it.copy(haptics = on) }
    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setHighContrast(on: Boolean) = update { it.copy(highContrast = on) }
    fun setReduceMotion(on: Boolean) = update { it.copy(reduceMotion = on) }
    fun setVoiceCommands(on: Boolean) = update { it.copy(voiceCommands = on) }
    fun setFontChoice(choice: FontChoice) = update { it.copy(fontChoice = choice) }
    fun setCameraFacing(facing: CameraFacing) = update { it.copy(cameraFacing = facing) }

    fun setTextSize(size: TextSize) {
        update { it.copy(textScale = size.scale) }
        speech.say("Text size: ${size.label}.", Priority.Interrupt)
    }

    // ---- voices ----------------------------------------------------------------------------

    /** Voices installed for the current language that work offline. May be empty while the engine starts. */
    fun voices(): List<VoiceOption> = voiceOptions(speech.availableVoices())

    /** Pick a voice (null = the phone's default), save it, and let her hear it. */
    fun chooseVoice(name: String?) {
        update { it.copy(voiceName = name) }
        speech.setVoice(name)
        speech.say("Hello, this is how I sound.", Priority.Interrupt)
    }

    /** Label for the Settings row. */
    fun currentVoiceLabel(): String {
        val name = settings.value.voiceName ?: return "Phone default"
        return voices().firstOrNull { it.name == name }?.label ?: "Phone default"
    }

    fun replayPrivacy() {
        speech.say(Spoken.PRIVACY, Priority.Interrupt)
    }

    // ---- developer mode --------------------------------------------------------------------

    /** Returns a spoken hint while tapping, or null. Seven taps unlock; each tap after is ignored. */
    fun onVersionTapped(): String? {
        if (settings.value.developerMode) return null
        versionTaps++
        val left = DEVELOPER_TAPS - versionTaps
        return when {
            left <= 0 -> {
                versionTaps = 0
                update { it.copy(developerMode = true) }
                "Developer tools are on."
            }
            left <= 3 -> "$left more ${if (left == 1) "tap" else "taps"} to turn on developer tools."
            else -> null
        }
    }

    fun say(text: String) = speech.say(text, Priority.Interrupt)
}
