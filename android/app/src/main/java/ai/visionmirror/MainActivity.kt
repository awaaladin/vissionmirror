package ai.visionmirror

import ai.visionmirror.app.AppViewModel
import ai.visionmirror.app.HardwareKeys
import ai.visionmirror.app.VisionMirrorRoot
import ai.visionmirror.audio.EarconPlayer
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.haptics.HapticsManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var speech: SpeechManager
    @Inject lateinit var haptics: HapticsManager
    @Inject lateinit var earcons: EarconPlayer
    @Inject lateinit var keys: HardwareKeys

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash stays up until settings are loaded, so we know whether to show onboarding.
        installSplashScreen().setKeepOnScreenCondition { appViewModel.settings.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VisionMirrorRoot(speech, haptics, earcons) }
    }

    /** Volume keys take a photo, but only while the Mirror is showing; elsewhere they change volume. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keys.captureEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.repeatCount == 0) keys.onVolumeKey()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        speech.stop()
        super.onStop()
    }
}
