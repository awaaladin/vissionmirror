package ai.visionmirror.design.tokens

import ai.visionmirror.R
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Atkinson Hyperlegible Next: drawn by the Braille Institute so that similar letters
 * (I / l / 1, O / 0, rn / m) stay distinguishable at low vision. One variable font, four weights.
 * Body is >= 22 sp and headings >= 32 sp. Sizes are in sp so they follow system font scale.
 */
@OptIn(ExperimentalTextApi::class)
val AtkinsonNext = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            resId = R.font.atkinson_next,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

@Immutable
data class VmType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val bodyLarge: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    /** For counters, countdowns and percentages: digits keep a fixed width so nothing jitters. */
    val numeric: TextStyle,
)

/** The reader's choice of typeface. Hyperlegible is the default; Standard is the phone's own sans-serif. */
enum class FontChoice(val label: String, val family: FontFamily) {
    Hyperlegible("Hyperlegible", AtkinsonNext),
    Standard("Standard", FontFamily.SansSerif),
}

private fun style(family: FontFamily, size: TextUnit, line: TextUnit, weight: Int, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size,
    lineHeight = line,
    letterSpacing = tracking,
)

fun vmTypeFor(family: FontFamily) = VmType(
    display = style(family, 48.sp, 54.sp, 700, (-0.5).sp),
    headline = style(family, 34.sp, 42.sp, 600, (-0.2).sp),
    title = style(family, 28.sp, 36.sp, 600),
    bodyLarge = style(family, 26.sp, 38.sp, 400),
    body = style(family, 22.sp, 32.sp, 400, 0.1.sp),
    label = style(family, 22.sp, 28.sp, 600, 0.2.sp),
    numeric = style(family, 28.sp, 34.sp, 600).copy(fontFeatureSettings = "tnum"),
)

val DefaultVmType = vmTypeFor(AtkinsonNext)

val LocalVmType = staticCompositionLocalOf { DefaultVmType }
