package ai.visionmirror.look

import android.graphics.Bitmap
import kotlin.math.pow
import kotlin.math.sqrt

/** Names a colour the way a person would say it: "navy blue", "olive green", not a hex code. */
object ColourNamer {
    private class Named(val name: String, rgb: Int) {
        val lab = toLab((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private val palette = listOf(
        Named("black", 0x101010), Named("charcoal grey", 0x36393D), Named("dark grey", 0x5A5D61),
        Named("grey", 0x8A8D91), Named("light grey", 0xBFC2C6), Named("white", 0xF5F5F2),
        Named("cream", 0xF2EBD3), Named("beige", 0xD2B98E), Named("tan", 0xB28B5A), Named("brown", 0x6B4426),
        Named("dark brown", 0x3E2A1B), Named("red", 0xC62828), Named("dark red", 0x8E1B1B),
        Named("maroon", 0x5E1224), Named("pink", 0xF19CB7), Named("hot pink", 0xE91E78),
        Named("orange", 0xF28C1C), Named("burnt orange", 0xB85A1A), Named("yellow", 0xF5D31E),
        Named("mustard", 0xC9A21B), Named("gold", 0xC9A227), Named("olive green", 0x6B7022),
        Named("lime green", 0x8CCB2F), Named("green", 0x2E8B3D), Named("dark green", 0x1D4D2B),
        Named("teal", 0x1B7F86), Named("turquoise", 0x30C4C0), Named("light blue", 0x9CCBEE),
        Named("sky blue", 0x4FA8E6), Named("blue", 0x2457C5), Named("royal blue", 0x1A3FA8),
        Named("navy blue", 0x14213F), Named("purple", 0x6B3FA0), Named("lavender", 0xB9A6E0),
        Named("violet", 0x8E24AA),
    )

    fun name(r: Int, g: Int, b: Int): String {
        val lab = toLab(r, g, b)
        return palette.minBy { distance(it.lab, lab) }.name
    }

    /** The colour of the middle of the picture (the part she is pointing at), averaged. */
    fun nameOfCentre(bitmap: Bitmap): String {
        val size = minOf(bitmap.width, bitmap.height) / 5
        val x0 = bitmap.width / 2 - size / 2
        val y0 = bitmap.height / 2 - size / 2
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, x0, y0, size, size)
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
        val n = pixels.size.coerceAtLeast(1)
        return name((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))

    private fun toLab(r: Int, g: Int, b: Int): DoubleArray {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val lr = lin(r); val lg = lin(g); val lb = lin(b)
        val x = (0.4124 * lr + 0.3576 * lg + 0.1805 * lb) / 0.95047
        val y = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb
        val z = (0.0193 * lr + 0.1192 * lg + 0.9505 * lb) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}
