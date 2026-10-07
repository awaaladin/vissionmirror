package ai.visionmirror.design.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/** 4 dp base grid. Generous by default: this app has very few elements per screen. */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val huge = 72.dp

    /** Every interactive element is at least this big; 64 dp is preferred for primary targets. */
    val minTouch = 56.dp
    val preferredTouch = 64.dp
}

/**
 * Concentric radii: a child inside a padded parent uses `parent - padding`, so the curves
 * share a centre and the gap between them stays visually even.
 */
object Radius {
    val xs = 12.dp
    val sm = 18.dp
    val md = 24.dp
    val lg = 32.dp
    val xl = 44.dp

    fun inner(outer: Dp, padding: Dp): Dp = (outer - padding).coerceAtLeast(2.dp)
    fun shape(radius: Dp): Shape = RoundedCornerShape(radius)
}

/** Elevation levels. Shadows are tinted with the base hue rather than black. */
object Elevation {
    val none = 0.dp
    val low = 4.dp
    val medium = 12.dp
    val high = 24.dp
}

fun Modifier.tintedShadow(elevation: Dp, shape: Shape, tint: Color): Modifier =
    if (elevation <= 0.dp) this else shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = tint.copy(alpha = 0.6f),
        spotColor = tint,
    )
