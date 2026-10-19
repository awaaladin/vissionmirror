package ai.visionmirror.settings

import ai.visionmirror.app.Spoken
import ai.visionmirror.audio.Persona
import ai.visionmirror.audio.ResponseLanguage
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.audio.SpeechManager.Priority
import ai.visionmirror.audio.say
import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.data.api.AppError
import ai.visionmirror.data.auth.AccountRepository
import ai.visionmirror.data.auth.AccountState
import ai.visionmirror.data.settings.CameraFacing
import ai.visionmirror.data.settings.Settings
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.design.tokens.FontChoice
import ai.visionmirror.design.tokens.ThemeMode
import android.speech.tts.Voice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val accounts: AccountRepository,
    private val voice: ai.visionmirror.audio.VoiceInput,
    private val wake: ai.visionmirror.audio.WakeSource,
) : ViewModel() {

    val account: StateFlow<AccountState> = accounts.state

    /** Deleting an account cannot be undone, so it takes two taps. */
    private val _confirmDelete = MutableStateFlow(false)
    val confirmDelete: StateFlow<Boolean> = _confirmDelete

    fun signOut() {
        viewModelScope.launch {
            accounts.signOut()
            speech.say("Signed out. You're using the app as a guest.", Priority.Interrupt)
        }
    }

    fun deleteAccount() {
        if (!_confirmDelete.value) {
            _confirmDelete.value = true
            speech.say("Tap again to permanently delete your account. This cannot be undone.", Priority.Interrupt)
            return
        }
        _confirmDelete.value = false
        viewModelScope.launch {
            accounts.deleteAccount()
                .onSuccess { speech.say("Your account was deleted.", Priority.Interrupt) }
                .onFailure { speech.say((it as? AppError)?.spokenText ?: "I couldn't delete your account. Please try again.", Priority.Interrupt) }
        }
    }

    fun dismissExpiredNote() {
        viewModelScope.launch { accounts.dismissExpiredNote() }
    }

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

    fun setLanguage(language: ResponseLanguage) {
        // A voice picked for another language would be silent or wrong here; go back to the best one for this language.
        update { it.copy(language = language, voiceName = null) }
        speech.setLanguage(language.speechLocales())
        speech.say("${language.label}.", Priority.Interrupt)
    }

    fun setPersona(persona: Persona) {
        update { it.copy(persona = persona, voiceName = null) }
        speech.setPersona(persona)
        speech.say("Hello, I'm ${persona.displayName}.", Priority.Interrupt)
    }

    fun setBargeIn(on: Boolean) = update { it.copy(bargeIn = on) }

    /** What the "Teach me your voice" exercise is doing right now, in words; null when it is not running. */
    private val _teaching = MutableStateFlow<String?>(null)
    val teaching: StateFlow<String?> = _teaching

    /**
     * She says [persona]'s name three times. Whatever the phone actually hears for each (even if it is "adina" or "pay ya")
     * is saved, so from then on that is also taken as the name. Nothing is recorded or sent anywhere.
     */
    fun teach(persona: Persona) {
        if (_teaching.value != null) return
        viewModelScope.launch {
            val name = persona.displayName
            val learned = LinkedHashSet<String>()
            wake.hold(true) // the name listener must stay off the microphone while she is being taught
            try {
                for (round in 1..3) {
                    _teaching.value = "Say $name. ($round of 3)"
                    speech.say("Say $name.", Priority.Interrupt)
                    kotlinx.coroutines.delay(1_800)
                    voice.start("en-NG")
                    val event = kotlinx.coroutines.withTimeoutOrNull(9_000) {
                        voice.events.first { it is ai.visionmirror.audio.VoiceEvent.Final || it is ai.visionmirror.audio.VoiceEvent.Failed }
                    }
                    voice.cancel()
                    val heard = (event as? ai.visionmirror.audio.VoiceEvent.Final)?.text.orEmpty()
                    _teaching.value = if (heard.isBlank()) "I didn't hear that one." else "I heard: $heard"
                    ai.visionmirror.audio.WakeWord.acceptAlias(persona, heard)?.let(learned::add)
                    kotlinx.coroutines.delay(900)
                }
                if (learned.isEmpty()) {
                    speech.say("I heard $name clearly already, or I couldn't catch it. Nothing needed changing.", Priority.Interrupt)
                } else {
                    update { s -> s.copy(nameAliases = (s.nameAliases + learned).distinct().takeLast(24)) }
                    speech.say("Thank you. I will also listen for how I heard you say $name.", Priority.Interrupt)
                }
            } finally {
                voice.cancel()
                wake.hold(false)
                _teaching.value = null
            }
        }
    }

    fun forgetLearnedNames() {
        update { it.copy(nameAliases = emptyList()) }
        speech.say("I forgot how you say the names. I will listen the normal way.", Priority.Interrupt)
    }

    /** Saves the one person who is told where she is when she asks for help. Typed or chosen from her contacts. */
    fun saveEmergencyContact(name: String, number: String) {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.length < 5) {
            speech.say("That phone number looks too short. Please check it.", Priority.Interrupt)
            return
        }
        val who = name.trim().ifBlank { "My emergency contact" }
        update { it.copy(emergencyContacts = listOf(ai.visionmirror.data.settings.EmergencyContact(who, digits))) }
        speech.say("Saved. $who is your emergency contact.", Priority.Interrupt)
    }

    fun clearEmergencyContact() {
        update { it.copy(emergencyContacts = emptyList()) }
        speech.say("Your emergency contact was removed.", Priority.Interrupt)
    }
    fun setHandsFree(on: Boolean) {
        update { it.copy(handsFree = on) }
        speech.say(if (on) "Hands free is on. I'll keep listening." else "Hands free is off.", Priority.Interrupt)
    }

    fun setBrightness(value: Float) {
        update { it.copy(brightness = value) }
    }

    fun setWakeWord(on: Boolean) {
        update { it.copy(wakeWord = on) }
    }

    fun setQuietListening(on: Boolean) {
        update { it.copy(quietListening = on) }
        speech.say(if (on) "I'll listen for my name." else "I won't listen for my name.", Priority.Interrupt)
    }

    fun setCloudVoice(on: Boolean) {
        update { it.copy(cloudVoice = on) }
        speech.setCloudVoice(on)
        speech.say("Hello, this is how I sound.", Priority.Interrupt)
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
