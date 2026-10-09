package ai.visionmirror.app

import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.data.auth.AccountRepository
import ai.visionmirror.data.auth.AccountState
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.haptics.HapticsManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-wide: exposes settings (null until loaded, which is what the splash waits for) and pushes
 * them into the shared speech and haptics managers.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val store: SettingsStore,
    speech: SpeechManager,
    haptics: HapticsManager,
    accounts: AccountRepository,
) : ViewModel() {

    /** Also makes sure the saved sign-in is loaded before the first request is made. */
    val account: StateFlow<AccountState> = accounts.state

    val settings: StateFlow<Settings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            store.settings.collect { s ->
                speech.setRate(s.speechRate)
                speech.setVoice(s.voiceName)
                haptics.enabled.value = s.haptics
            }
        }
    }
}
