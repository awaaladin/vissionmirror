package ai.visionmirror.app

import ai.visionmirror.audio.EarconPlayer
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.design.tokens.VisionMirrorTheme
import ai.visionmirror.haptics.HapticsManager
import ai.visionmirror.session.SessionViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController

/**
 * Applies the user's settings (theme mode, High Contrast, reduce motion, haptics) to everything
 * below, and picks the start destination: first launch goes through onboarding, returning
 * visits go straight to the Mirror.
 */
@Composable
fun VisionMirrorRoot(
    speech: SpeechManager,
    haptics: HapticsManager,
    earcons: EarconPlayer,
    appViewModel: AppViewModel = hiltViewModel(),
    session: SessionViewModel = hiltViewModel(),
) {
    val loaded by appViewModel.settings.collectAsStateWithLifecycle()
    val settings = loaded ?: return // the system splash stays up until settings have loaded
    // Decided once: changing settings later must not teleport her to another screen.
    val start = remember { if (settings.onboardingDone) Routes.MIRROR else Routes.ONBOARDING }

    VisionMirrorTheme(
        themeMode = settings.themeMode,
        highContrast = settings.highContrast,
        reduceMotion = settings.reduceMotion,
        fontChoice = settings.fontChoice,
        textScale = settings.textScale,
        haptics = haptics,
    ) {
        AppNav(rememberNavController(), start, session, speech, haptics, earcons)
    }
}
