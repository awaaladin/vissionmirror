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
    val account by appViewModel.account.collectAsStateWithLifecycle()
    if (!account.loaded) return
    // She has not yet chosen between an account and guest mode.
    val needsAuth = !account.signedIn && !account.guestChosen
    // Decided once: changing settings later must not teleport her to another screen.
    val start = remember {
        when {
            !settings.onboardingDone -> Routes.ONBOARDING
            needsAuth -> Routes.AUTH
            else -> Routes.MIRROR
        }
    }

    VisionMirrorTheme(
        themeMode = settings.themeMode,
        highContrast = settings.highContrast,
        reduceMotion = settings.reduceMotion,
        fontChoice = settings.fontChoice,
        textScale = settings.textScale,
        haptics = haptics,
    ) {
        AppNav(rememberNavController(), start, needsAuth, session, speech, haptics, earcons)
    }
}
