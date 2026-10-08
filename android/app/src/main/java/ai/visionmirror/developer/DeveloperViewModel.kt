package ai.visionmirror.developer

import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.MirrorApi
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.data.settings.SettingsStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeveloperUi(
    val checking: Boolean = false,
    val connectionResult: String? = null,
    val note: String? = null,
    val voiceInfo: String = "",
)

@HiltViewModel
class DeveloperViewModel @Inject constructor(
    private val api: MirrorApi,
    private val store: SettingsStore,
    private val speech: SpeechManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(DeveloperUi(voiceInfo = "${speech.availableVoices().size} offline voice(s) for this language"))
    val ui: StateFlow<DeveloperUi> = _ui

    /** A real call to the live server's /v1/health, timed. */
    fun checkConnection() {
        if (_ui.value.checking) return
        _ui.update { it.copy(checking = true, connectionResult = null) }
        viewModelScope.launch {
            val started = System.nanoTime()
            val result = try {
                val r = api.health()
                val ms = (System.nanoTime() - started) / 1_000_000
                if (r.isSuccessful) "Connected. The server answered in $ms ms." else "The server answered with an error: HTTP ${r.code()}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not reach the server (${e.javaClass.simpleName}). Check the phone's internet connection."
            }
            _ui.update { it.copy(checking = false, connectionResult = result) }
            speech.say(result, Priority.Interrupt)
        }
    }

    fun speakTest() {
        speech.say("This is a test of the voice you chose. One, two, three.", Priority.Interrupt)
    }

    fun replayOnboarding() {
        viewModelScope.launch { store.update { it.copy(onboardingDone = false) } }
        _ui.update { it.copy(note = "The welcome tour will show the next time you open the app.") }
    }

    fun resetSettings() {
        viewModelScope.launch {
            // Keep developer mode on so you don't have to unlock it again, and keep the tour as already seen.
            store.update { Settings(onboardingDone = it.onboardingDone, developerMode = true) }
            speech.setVoice(null)
        }
        _ui.update { it.copy(note = "All settings are back to their defaults.") }
        speech.say("Settings reset.", Priority.Interrupt)
    }

    fun turnOff() {
        viewModelScope.launch { store.update { it.copy(developerMode = false) } }
    }
}
