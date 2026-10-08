package ai.visionmirror.design.tokens

import ai.visionmirror.haptics.Haptics
import ai.visionmirror.haptics.LocalHaptics
import ai.visionmirror.haptics.NoopHaptics
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Convenient access: `Vm.colors.brass`, `Vm.type.body`. */
object Vm {
    val colors: VmColors @Composable get() = LocalVmColors.current
    val type: VmType @Composable get() = LocalVmType.current
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current
}

/** Settings > Appearance. High Contrast is a separate switch and overrides this. */
enum class ThemeMode(val label: String) {
    System("System"),
    Dark("Dark"),
    Light("Light"),
}

/**
 * Material 3 is only the foundation here (text selection, system bars, motion scheme).
 * Everything visible is built from our own components on `foundation` primitives.
 */
@Composable
fun VisionMirrorTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    highContrast: Boolean = false,
    reduceMotion: Boolean = false,
    fontChoice: FontChoice = FontChoice.Hyperlegible,
    /** Multiplies the phone's font size setting, so the reader can go bigger (or smaller) than the system. */
    textScale: Float = 1.0f,
    haptics: Haptics = NoopHaptics,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val colors = when {
        highContrast -> VmHighContrastColors
        dark -> VmDarkColors
        else -> VmLightColors
    }
    val effectiveReduce = reduceMotion || rememberSystemReduceMotion()
    val scheme = run {
        if (colors.isLight) {
            lightColorScheme(
                primary = colors.brass, onPrimary = colors.onBrass,
                background = colors.ink, onBackground = colors.bone,
                surface = colors.surface, onSurface = colors.bone,
                surfaceVariant = colors.raised, onSurfaceVariant = colors.boneMuted,
                outline = colors.hairline, error = colors.ember,
            )
        } else {
            darkColorScheme(
                primary = colors.brass, onPrimary = colors.onBrass,
                background = colors.ink, onBackground = colors.bone,
                surface = colors.surface, onSurface = colors.bone,
                surfaceVariant = colors.raised, onSurfaceVariant = colors.boneMuted,
                outline = colors.hairline, error = colors.ember,
            )
        }
    }

    // Keep status/navigation bar icons readable on whichever ground we chose.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = colors.isLight
                    isAppearanceLightNavigationBars = colors.isLight
                }
            }
        }
    }

    val system = LocalDensity.current
    val density = remember(system, textScale) { Density(system.density, system.fontScale * textScale) }
    val type = remember(fontChoice) { vmTypeFor(fontChoice.family) }

    CompositionLocalProvider(
        LocalDensity provides density,
        LocalVmColors provides colors,
        LocalVmType provides type,
        LocalReduceMotion provides effectiveReduce,
        LocalHaptics provides haptics,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
