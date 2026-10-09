package ai.visionmirror.app

import ai.visionmirror.account.AuthScreen
import ai.visionmirror.audio.EarconPlayer
import ai.visionmirror.audio.SpeechManager
import ai.visionmirror.design.tokens.Motion
import ai.visionmirror.haptics.HapticsManager
import ai.visionmirror.lab.DesignLabScreen
import ai.visionmirror.mirror.MirrorScreen
import ai.visionmirror.onboarding.OnboardingScreen
import ai.visionmirror.session.AnalysingScreen
import ai.visionmirror.session.ResultScreen
import ai.visionmirror.session.SessionViewModel
import ai.visionmirror.developer.DeveloperScreen
import ai.visionmirror.settings.SettingsScreen
import ai.visionmirror.settings.VoicePickerScreen
import ai.visionmirror.ui.LocalNavAnimatedScope
import ai.visionmirror.ui.LocalSharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

object Routes {
    const val ONBOARDING = "onboarding"
    const val MIRROR = "mirror"
    const val ANALYSING = "analysing"
    const val RESULT = "result"
    const val SETTINGS = "settings"
    const val LAB = "lab"
    const val VOICES = "voices"
    const val AUTH = "auth"
    const val ACCOUNT = "account"
    const val DEVELOPER = "developer"
}

/**
 * Single NavHost wrapped in a SharedTransitionLayout: the Halo is one element that persists and
 * morphs across screens. Predictive back is on (manifest + Navigation's seekable transitions), so
 * a back swipe scrubs the same transition instead of cutting.
 *
 * Transitions: entrances start at scale 0.95 + alpha 0 (never from nothing), exits are quick and
 * do not overshoot. Reduce-motion collapses them to the default cross-fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNav(
    nav: NavHostController,
    start: String,
    needsAuth: Boolean,
    session: SessionViewModel,
    speech: SpeechManager,
    haptics: HapticsManager,
    earcons: EarconPlayer,
) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = nav,
                startDestination = start,
                enterTransition = { fadeIn(Motion.fade()) + scaleIn(Motion.settle(), initialScale = 0.95f) },
                exitTransition = { fadeOut(Motion.fade()) },
                popEnterTransition = { fadeIn(Motion.fade()) },
                popExitTransition = { fadeOut(Motion.fade()) + scaleOut(Motion.critical(), targetScale = 0.95f) },
            ) {
                screen(Routes.ONBOARDING) {
                    OnboardingScreen(
                        speaker = speech,
                        onFinished = {
                            val next = if (needsAuth) Routes.AUTH else Routes.MIRROR
                            nav.navigate(next) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                        },
                    )
                }
                screen(Routes.AUTH) {
                    AuthScreen(
                        firstRun = true,
                        onDone = { nav.navigate(Routes.MIRROR) { popUpTo(Routes.AUTH) { inclusive = true } } },
                        onBack = null,
                    )
                }
                screen(Routes.ACCOUNT) {
                    AuthScreen(firstRun = false, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
                }
                screen(Routes.MIRROR) {
                    MirrorScreen(
                        session = session,
                        onCaptured = { nav.navigate(Routes.ANALYSING) },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                screen(Routes.ANALYSING) {
                    AnalysingScreen(
                        session = session,
                        onDone = {
                            nav.navigate(Routes.RESULT) { popUpTo(Routes.ANALYSING) { inclusive = true } }
                        },
                        onBack = { nav.popBackStack(Routes.MIRROR, inclusive = false) },
                    )
                }
                screen(Routes.RESULT) {
                    ResultScreen(
                        session = session,
                        speaker = speech,
                        onRetake = { nav.popBackStack(Routes.MIRROR, inclusive = false) },
                    )
                }
                screen(Routes.SETTINGS) {
                    SettingsScreen(
                        session = session,
                        speaker = speech,
                        onBack = { nav.popBackStack() },
                        onOpenVoices = { nav.navigate(Routes.VOICES) },
                        onOpenAccount = { nav.navigate(Routes.ACCOUNT) },
                        onOpenDeveloper = { nav.navigate(Routes.DEVELOPER) },
                    )
                }
                screen(Routes.VOICES) {
                    VoicePickerScreen(onBack = { nav.popBackStack() })
                }
                screen(Routes.DEVELOPER) {
                    DeveloperScreen(
                        onBack = { nav.popBackStack() },
                        onOpenLab = { nav.navigate(Routes.LAB) },
                    )
                }
                screen(Routes.LAB) {
                    DesignLabScreen(speech = speech, haptics = haptics, earcons = earcons)
                }
            }
        }
    }
}

/** A destination that also tells [ai.visionmirror.ui.sharedHalo] which transition it belongs to. */
private fun androidx.navigation.NavGraphBuilder.screen(route: String, content: @Composable () -> Unit) {
    composable(route) {
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content() }
    }
}
