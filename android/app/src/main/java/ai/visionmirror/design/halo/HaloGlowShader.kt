package ai.visionmirror.design.halo

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * AGSL glow for API 33+. Draws only the soft bloom around the ring (the crisp ring itself is
 * drawn by Canvas on top). Older devices use a layered-stroke fallback in [Halo].
 * Output is premultiplied alpha, as AGSL requires.
 */
@RequiresApi(33)
internal class HaloGlowShader {
    private val shader = RuntimeShader(SRC)
    val brush = ShaderBrush(shader)

    fun update(
        cx: Float,
        cy: Float,
        radius: Float,
        thickness: Float,
        glow: Float,
        angle: Float,
        shimmer: Float,
        colorA: Color,
        colorB: Color,
    ) {
        shader.setFloatUniform("center", cx, cy)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("thick", thickness)
        shader.setFloatUniform("glow", glow)
        shader.setFloatUniform("angle", angle)
        shader.setFloatUniform("shimmer", shimmer)
        shader.setFloatUniform("colorA", colorA.red, colorA.green, colorA.blue)
        shader.setFloatUniform("colorB", colorB.red, colorB.green, colorB.blue)
    }

    private companion object {
        const val SRC = """
uniform float2 center;
uniform float radius;
uniform float thick;
uniform float glow;
uniform float angle;
uniform float shimmer;
uniform float3 colorA;
uniform float3 colorB;

half4 main(float2 p) {
    float2 d = p - center;
    float dist = abs(length(d) - radius);
    float near = exp(-dist / (thick * 3.0));
    float wide = exp(-dist / (thick * 9.0)) * 0.5;

    float a = atan(d.y, d.x);
    float da = abs(mod(a - angle + 3.14159265, 6.2831853) - 3.14159265);
    float sweep = shimmer * exp(-da * da * 5.0);

    float t = clamp((0.5 + 0.5 * sin(a * 2.0 + angle)) * 0.6 + sweep, 0.0, 1.0);
    float3 col = mix(colorA, colorB, t);
    float i = clamp((near * 0.55 + wide * 0.45) * glow * (1.0 + sweep * 1.2), 0.0, 1.0);
    return half4(half3(col * i), half(i));
}
"""
    }
}
