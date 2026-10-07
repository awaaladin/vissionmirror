package ai.visionmirror.design.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * "Silvered glass at dusk". Every neutral is tinted toward the Ink hue (cool, slightly blue)
 * and text is warm bone, so the pair never reads as flat grey-on-black.
 *
 * Meaning is never carried by colour alone: Ember always travels with an icon and words.
 */
@Immutable
data class VmColors(
    val ink: Color,
    val surface: Color,
    val raised: Color,
    val hairline: Color,
    val bone: Color,
    val boneMuted: Color,
    val brass: Color,
    val champagne: Color,
    val quartz: Color,
    val ember: Color,
    /** Content colour on top of Brass / Champagne fills. */
    val onBrass: Color,
    /** Hue used to tint shadows, instead of black. */
    val shadowTint: Color,
    val isHighContrast: Boolean,
    val isLight: Boolean = false,
) {
    /** Brass-to-champagne ramp used for primary fills and the Halo ring. */
    val brassRamp: Brush get() = Brush.verticalGradient(listOf(champagne, brass))
}

val VmDarkColors = VmColors(
    ink = Color(0xFF0C0D10),
    surface = Color(0xFF14161B),
    raised = Color(0xFF1B1E25),
    hairline = Color(0xFF2A2D36),
    bone = Color(0xFFEFE9DD),
    boneMuted = Color(0xFFB9B3A6),
    brass = Color(0xFFE0A458),
    champagne = Color(0xFFF5D9A0),
    quartz = Color(0xFFF2A8B8),
    ember = Color(0xFFFF7A59),
    onBrass = Color(0xFF0C0D10),
    shadowTint = Color(0xFF05060A),
    isHighContrast = false,
)

/**
 * "Paper at dawn": the same personality on a warm paper ground. Brass becomes a deep bronze so
 * controls and text keep >= 7:1; Quartz and Ember are deepened for the same reason.
 */
val VmLightColors = VmColors(
    ink = Color(0xFFF6F1E7),
    surface = Color(0xFFFBF8F1),
    raised = Color(0xFFFDFBF6),
    hairline = Color(0xFFD8D0C0),
    bone = Color(0xFF1B1710),
    boneMuted = Color(0xFF4A4438),
    brass = Color(0xFF5F3A06),
    champagne = Color(0xFF77490A),
    quartz = Color(0xFF9A2F52),
    ember = Color(0xFF9E2C12),
    onBrass = Color(0xFFFBF8F1),
    shadowTint = Color(0xFF3A2E1A),
    isHighContrast = false,
    isLight = true,
)

/** True High Contrast: pure black, white, yellow. Hairlines become full-strength outlines. */
val VmHighContrastColors = VmColors(
    ink = Color(0xFF000000),
    surface = Color(0xFF000000),
    raised = Color(0xFF000000),
    hairline = Color(0xFFFFFFFF),
    bone = Color(0xFFFFFFFF),
    boneMuted = Color(0xFFFFFFFF),
    brass = Color(0xFFFFD400),
    champagne = Color(0xFFFFD400),
    quartz = Color(0xFFFFFFFF),
    ember = Color(0xFFFFD400),
    onBrass = Color(0xFF000000),
    shadowTint = Color(0xFF000000),
    isHighContrast = true,
)

val LocalVmColors = staticCompositionLocalOf { VmDarkColors }
