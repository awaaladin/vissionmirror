package ai.visionmirror

import ai.visionmirror.assistant.VoiceCatalog
import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VisionMirrorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Every voice command and its synonyms (the same list the website and docs/VOICE_COMMANDS.md show).
        runCatching { VoiceCatalog.install(assets.open("voice-commands.json").bufferedReader().use { it.readText() }) }
            .onFailure { Log.e("VisionMirror", "voice command catalog could not load", it) }
    }
}
