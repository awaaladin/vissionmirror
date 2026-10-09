package ai.visionmirror.account

import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.Speaker
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.auth.AccountRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode(val label: String) { SignIn("Sign in"), SignUp("Create account") }

data class AuthUi(
    val mode: AuthMode = AuthMode.SignIn,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val busy: Boolean = false,
    /** A sentence that is both shown and spoken. */
    val error: String? = null,
    /** Set when sign-in/up worked or she chose guest: the screen can close. */
    val done: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val speaker: Speaker,
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUi())
    val ui: StateFlow<AuthUi> = _ui

    fun announce(firstRun: Boolean) {
        speaker.say(
            if (firstRun) "Sign in or create an account to get started. Or continue as a guest."
            else "Sign in or create an account.",
            Priority.Interrupt,
        )
    }

    fun setMode(mode: AuthMode) = _ui.update { it.copy(mode = mode, error = null) }
    fun setName(v: String) = _ui.update { it.copy(name = v) }
    fun setEmail(v: String) = _ui.update { it.copy(email = v, error = null) }
    fun setPassword(v: String) = _ui.update { it.copy(password = v, error = null) }
    fun toggleShowPassword() = _ui.update { it.copy(showPassword = !it.showPassword) }

    fun submit() {
        val s = _ui.value
        if (s.busy) return
        when {
            s.email.isBlank() -> return fail("Please type your email address.")
            s.password.isEmpty() -> return fail("Please type your password.")
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = if (s.mode == AuthMode.SignIn) {
                accounts.login(s.email, s.password)
            } else {
                accounts.register(s.email, s.password, s.name)
            }
            result
                .onSuccess {
                    speaker.say(if (s.mode == AuthMode.SignIn) "Signed in. Welcome back." else "Your account is ready. Welcome.", Priority.Interrupt)
                    _ui.update { it.copy(busy = false, password = "", done = true) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false) }
                    fail((e as? AppError)?.spokenText ?: "Something went wrong. Please try again.")
                }
        }
    }

    fun continueAsGuest() {
        viewModelScope.launch {
            accounts.chooseGuest()
            _ui.update { it.copy(done = true) }
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
        speaker.say(message, Priority.Interrupt)
    }
}
