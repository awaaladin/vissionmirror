package ai.visionmirror.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.staticCompositionLocalOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Haptic vocabulary. Each pattern is distinct so a blind user can tell them apart by feel:
 * tick = press, lowTick = soft release, click = confirm, ready = framing is good,
 * error = something failed, proximity = "you are getting closer".
 */
interface Haptics {
    fun tick()
    fun lowTick()
    fun click()
    fun ready()
    fun error()

    /** [closeness] 0..1. Call repeatedly; [proximityIntervalMs] says how often. */
    fun proximity(closeness: Float)
    fun cancel()
}

object NoopHaptics : Haptics {
    override fun tick() = Unit
    override fun lowTick() = Unit
    override fun click() = Unit
    override fun ready() = Unit
    override fun error() = Unit
    override fun proximity(closeness: Float) = Unit
    override fun cancel() = Unit
}

/** Components read haptics from here so they stay stateless and previewable. */
val LocalHaptics = staticCompositionLocalOf<Haptics> { NoopHaptics }

/** Ticks speed up as framing improves: 700 ms when far, 120 ms when almost centred. */
fun proximityIntervalMs(closeness: Float): Long {
    val c = closeness.coerceIn(0f, 1f)
    return (700f + (120f - 700f) * c).toLong()
}

@Singleton
class HapticsManager @Inject constructor(
    @ApplicationContext context: Context,
) : Haptics {

    /** Settings > Haptics on/off. */
    val enabled = MutableStateFlow(true)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Rich primitives need API 30+ *and* hardware support for every primitive we use. */
    private val hasPrimitives: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        vibrator?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        ) == true

    private fun play(effect: VibrationEffect) {
        if (!enabled.value) return
        vibrator?.takeIf { it.hasVibrator() }?.vibrate(effect)
    }

    private fun oneShot(ms: Long, amplitude: Int): VibrationEffect =
        if (vibrator?.hasAmplitudeControl() == true) {
            VibrationEffect.createOneShot(ms, amplitude)
        } else {
            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }

    private fun primitive(id: Int, scale: Float, fallback: VibrationEffect): VibrationEffect =
        if (hasPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            VibrationEffect.startComposition().addPrimitive(id, scale).compose()
        } else {
            fallback
        }

    override fun tick() = play(
        primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, oneShot(8, 80)),
    )

    override fun lowTick() = play(
        primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f, oneShot(10, 60)),
    )

    override fun click() = play(
        primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, oneShot(15, 160)),
    )

    /** Rising low tick, click, bigger click: reads as "settled". */
    override fun ready() = play(
        if (hasPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 60)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 90)
                .compose()
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 15, 50, 30), intArrayOf(0, 120, 0, 220), -1)
        },
    )

    /** Three uneven buzzes: deliberately unlike anything else. */
    override fun error() = play(
        VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40, 60, 90), -1),
    )

    override fun proximity(closeness: Float) {
        val c = closeness.coerceIn(0f, 1f)
        play(
            primitive(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                0.3f + 0.7f * c,
                oneShot(8, (60 + 120 * c).toInt()),
            ),
        )
    }

    override fun cancel() {
        vibrator?.cancel()
    }
}
