package ai.visionmirror.assistant

import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.data.settings.SettingsStore
import ai.visionmirror.di.ApplicationScope
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The parts of "change anything by voice" that are the phone's, not a saved setting. Each returns what to say. */
interface DeviceControls {
    fun volume(steps: Int): String
    fun volumeMax(): String
    fun nextVoice(): String
}

@Singleton
class AndroidDeviceControls @Inject constructor(
    @ApplicationContext context: Context,
    private val speech: SpeechManager,
    private val settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : DeviceControls {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // She talks on the media volume, the one the phone's volume keys change.
    override fun volume(steps: Int): String {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (now + steps * (max / 5).coerceAtLeast(1)).coerceIn(1, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        return when {
            next == now && steps > 0 -> "That's already the loudest."
            next == now -> "That's already the quietest I can go."
            steps > 0 -> "Louder."
            else -> "Quieter."
        }
    }

    override fun volumeMax(): String {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        return "Full volume."
    }

    override fun nextVoice(): String {
        val voices = speech.availableVoices()
        if (voices.size < 2) return "This phone has no other voices installed."
        // Which one is in use is the saved choice; with none saved, we are on the first.
        val currentName = latestVoiceName
        val index = voices.indexOfFirst { it.name == currentName }
        val next = voices[(index + 1).mod(voices.size)]
        speech.setVoice(next.name)
        scope.launch { settings.update { it.copy(voiceName = next.name) } }
        return "Voice ${voices.indexOf(next) + 1} of ${voices.size}. This is how I sound."
    }

    @Volatile private var latestVoiceName: String? = null

    init {
        scope.launch { settings.settings.collect { latestVoiceName = it.voiceName } }
    }
}
