package ai.visionmirror.settings

import ai.visionmirror.app.Spoken
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.design.tokens.ThemeMode
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val speech: SpeechManager,
) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

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

    /** Cycles through the voices installed for the current language and previews the new one. */
    fun nextVoice() {
        val voices = speech.availableVoices()
        if (voices.isEmpty()) {
            speech.say("This phone has only one voice installed.", Priority.Interrupt)
            return
        }
        val current = settings.value.voiceName
        val next = voices[(voices.indexOfFirst { it.name == current } + 1) % voices.size]
        update { it.copy(voiceName = next.name) }
        speech.setVoice(next.name)
        speech.say("Hello, this is how I sound.", Priority.Interrupt)
    }

    fun replayPrivacy() {
        speech.say(Spoken.PRIVACY, Priority.Interrupt)
    }

    /** Short, human label for the current voice. */
    fun voiceLabel(): String = settings.value.voiceName?.substringAfterLast('-')?.takeIf { it.isNotBlank() } ?: "Default"
}
