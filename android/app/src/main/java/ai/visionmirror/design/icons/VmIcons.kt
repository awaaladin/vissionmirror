package ai.visionmirror.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Bespoke icon set. Rules: 24 dp grid, 2 dp stroke, round caps and joins, outline only,
 * 2 dp safe margin so shapes sit optically centred. Tinted at the call site (stroke is black here).
 * A zero-length segment such as "M12 17v.01" renders as a round dot.
 */
private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object VmIcons {
    /** The brand mark's body: a mirror oval with a highlight arc. */
    val Mirror = icon(
        "Mirror",
        "M12 3.5c-3.6 0-6 3.7-6 8.5s2.4 8.5 6 8.5 6-3.7 6-8.5-2.4-8.5-6-8.5z",
        "M9.3 8.4c.5-1.2 1.3-2 2.4-2.3",
    )
    val Play = icon("Play", "M8 5.5v13l10.5-6.5z")
    val Pause = icon("Pause", "M9 5.5v13", "M15 5.5v13")
    val Repeat = icon("Repeat", "M4.5 12a7.5 7.5 0 1 0 2.3-5.4", "M4.5 4.5v4h4")
    val Retake = icon("Retake", "M19.5 12a7.5 7.5 0 1 1-2.3-5.4", "M19.5 4.5v4h-4")
    val Capture = icon(
        "Capture",
        "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z",
        "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z",
    )
    val Mic = icon(
        "Mic",
        "M12 3.5a3 3 0 0 0-3 3V12a3 3 0 0 0 6 0V6.5a3 3 0 0 0-3-3z",
        "M6 11.5a6 6 0 0 0 12 0",
        "M12 17.5v3",
    )
    val Ask = icon(
        "Ask",
        "M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-8l-4.5 3.5v-3.5H5A1.5 1.5 0 0 1 3.5 15V7A1.5 1.5 0 0 1 5 5.5z",
    )
    val Settings = icon(
        "Settings",
        "M4 7h9", "M17 7h3", "M15 5v4",
        "M4 17h3", "M11 17h9", "M9 15v4",
    )
    val Check = icon("Check", "M5 12.5l4.5 4.5L19 7.5")
    val Close = icon("Close", "M6 6l12 12", "M18 6L6 18")
    val Back = icon("Back", "M14.5 5l-7 7 7 7")
    val Warning = icon("Warning", "M12 4.5l8.5 15h-17z", "M12 10v4", "M12 17v.01")
    val Detail = icon("Detail", "M4 7h16", "M4 12h11", "M4 17h6")
    val Outfit = icon(
        "Outfit",
        "M12 7.5v2L3.5 16a1.2 1.2 0 0 0 .8 2.1h15.4a1.2 1.2 0 0 0 .8-2.1L12 9.5",
        "M12 7.5a2 2 0 1 0-2-2",
    )
    val Speaker = icon(
        "Speaker",
        "M4 9.5h3.5L12 5.5v13l-4.5-4H4z",
        "M15.5 9a4 4 0 0 1 0 6",
        "M18 6.5a7.5 7.5 0 0 1 0 11",
    )
    val Lock = icon(
        "Lock",
        "M6.5 11h11a1 1 0 0 1 1 1v7.5a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1V12a1 1 0 0 1 1-1z",
        "M8.5 11V8a3.5 3.5 0 0 1 7 0v3",
    )
    val Offline = icon(
        "Offline",
        "M5 11.5a10 10 0 0 1 4-2.3", "M15 9.2a10 10 0 0 1 4 2.3",
        "M8 15a6 6 0 0 1 8 0", "M12 19v.01", "M4 4l16 16",
    )
    val Info = icon(
        "Info",
        "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z", "M12 11v5", "M12 8v.01",
    )
    val Palette = icon(
        "Palette",
        "M12 3.5a8.5 8.5 0 1 0 0 17c1.4 0 2-1 1.5-2.2-.5-1.3.2-2.3 1.5-2.3h2.2a3.3 3.3 0 0 0 3.3-3.3C20.5 7 16.7 3.5 12 3.5z",
        "M8 11v.01", "M10.5 7.5v.01", "M14.5 7.5v.01",
    )

    /** Switch between the front and back camera. */
    val Flip = icon("Flip", "M4.5 8.5h13", "M14 5l3.5 3.5L14 12", "M19.5 15.5h-13", "M10 12l-3.5 3.5L10 19")
    val TextSize = icon("TextSize", "M3.5 19L8 7l4.5 12", "M5 15h6", "M15.5 19l2.5-6 2.5 6", "M16.3 17h3.4")
    val Person = icon("Person", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M4.5 20c.8-3.6 3.7-5.5 7.5-5.5s6.7 1.9 7.5 5.5")
    val Tool = icon("Tool", "M14.5 6.5a4 4 0 0 0 4.8 4.8L9 21.5l-6.5-6.5L14 3.5", "M16 4l4 4")

    /** Everything, for the Design Lab. */
    val all: List<ImageVector> by lazy {
        listOf(
            Mirror, Play, Pause, Repeat, Retake, Capture, Mic, Ask, Settings, Check, Close, Back,
            Warning, Detail, Outfit, Speaker, Lock, Offline, Info, Palette, Flip, TextSize, Person, Tool,
        )
    }
}
