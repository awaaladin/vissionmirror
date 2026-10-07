package ai.visionmirror

import ai.visionmirror.design.tokens.VmColors
import ai.visionmirror.design.tokens.VmDarkColors
import ai.visionmirror.design.tokens.VmHighContrastColors
import ai.visionmirror.design.tokens.VmLightColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast for every text/control pairing the brief requires to be at least 7:1. */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, c: VmColors) {
        val pairs = mapOf(
            "bone on ink" to ratio(c.bone, c.ink),
            "bone on surface" to ratio(c.bone, c.surface),
            "bone on raised" to ratio(c.bone, c.raised),
            "muted on ink" to ratio(c.boneMuted, c.ink),
            "muted on surface" to ratio(c.boneMuted, c.surface),
            "brass on ink" to ratio(c.brass, c.ink),
            "onBrass on brass" to ratio(c.onBrass, c.brass),
            "onBrass on champagne" to ratio(c.onBrass, c.champagne),
            "ember on ink" to ratio(c.ember, c.ink),
            "ember on raised" to ratio(c.ember, c.raised),
            "quartz on ink" to ratio(c.quartz, c.ink),
        )
        val failures = pairs.filterValues { it < 7.0 }
        assertTrue("$name below 7:1: " + failures.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" }, failures.isEmpty())
    }

    @Test fun dark() = check("dark", VmDarkColors)
    @Test fun light() = check("light", VmLightColors)
    @Test fun highContrast() = check("highContrast", VmHighContrastColors)
}
