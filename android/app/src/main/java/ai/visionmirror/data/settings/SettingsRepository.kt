package ai.visionmirror.data.settings

import ai.visionmirror.data.api.DetailLevel
import ai.visionmirror.design.tokens.FontChoice
import ai.visionmirror.design.tokens.ThemeMode
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Which camera the Mirror uses. The front camera is the default: she can see the preview. */
enum class CameraFacing(val label: String) { Front("Front"), Back("Back") }

/** Everything in Settings. Defaults are chosen for a first-time blind user. */
data class Settings(
    val onboardingDone: Boolean = false,
    val speechRate: Float = 1.0f,
    val voiceName: String? = null,
    val detailLevel: DetailLevel = DetailLevel.Standard,
    val haptics: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.Dark,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    /** After the result is read, listen briefly for "ask", "retake", "repeat". */
    val voiceCommands: Boolean = true,
    /** Multiplies the phone's own font size setting. 1.0 = follow the phone exactly. */
    val textScale: Float = 1.0f,
    val fontChoice: FontChoice = FontChoice.Hyperlegible,
    val cameraFacing: CameraFacing = CameraFacing.Front,
    /** Unlocked by tapping the version line seven times; shows the Developer tools. */
    val developerMode: Boolean = false,
)

interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}

@Singleton
class DataStoreSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsStore {

    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val speechRate = floatPreferencesKey("speech_rate")
        val voiceName = stringPreferencesKey("voice_name")
        val detail = stringPreferencesKey("detail_level")
        val haptics = booleanPreferencesKey("haptics")
        val theme = stringPreferencesKey("theme_mode")
        val highContrast = booleanPreferencesKey("high_contrast")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val voiceCommands = booleanPreferencesKey("voice_commands")
        val textScale = floatPreferencesKey("text_scale")
        val fontChoice = stringPreferencesKey("font_choice")
        val cameraFacing = stringPreferencesKey("camera_facing")
        val developerMode = booleanPreferencesKey("developer_mode")
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
            speechRate = this[Keys.speechRate] ?: d.speechRate,
            voiceName = this[Keys.voiceName],
            detailLevel = DetailLevel.entries.firstOrNull { it.wire == this[Keys.detail] } ?: d.detailLevel,
            haptics = this[Keys.haptics] ?: d.haptics,
            themeMode = ThemeMode.entries.firstOrNull { it.name == this[Keys.theme] } ?: d.themeMode,
            highContrast = this[Keys.highContrast] ?: d.highContrast,
            reduceMotion = this[Keys.reduceMotion] ?: d.reduceMotion,
            voiceCommands = this[Keys.voiceCommands] ?: d.voiceCommands,
            textScale = (this[Keys.textScale] ?: d.textScale).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            fontChoice = FontChoice.entries.firstOrNull { it.name == this[Keys.fontChoice] } ?: d.fontChoice,
            cameraFacing = CameraFacing.entries.firstOrNull { it.name == this[Keys.cameraFacing] } ?: d.cameraFacing,
            developerMode = this[Keys.developerMode] ?: d.developerMode,
        )
    }

    override val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val old = p.toSettings()
            val s = transform(old)
            p[Keys.onboardingDone] = s.onboardingDone
            p[Keys.speechRate] = s.speechRate
            if (s.voiceName != null) p[Keys.voiceName] = s.voiceName else p.remove(Keys.voiceName)
            p[Keys.detail] = s.detailLevel.wire
            p[Keys.haptics] = s.haptics
            p[Keys.theme] = s.themeMode.name
            p[Keys.highContrast] = s.highContrast
            p[Keys.reduceMotion] = s.reduceMotion
            p[Keys.voiceCommands] = s.voiceCommands
            p[Keys.textScale] = s.textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
            p[Keys.fontChoice] = s.fontChoice.name
            p[Keys.cameraFacing] = s.cameraFacing.name
            p[Keys.developerMode] = s.developerMode
        }
    }

    companion object {
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 2.0f
    }
}
