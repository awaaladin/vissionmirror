package ai.visionmirror.guide

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.hypot

/** What the phone's own sensors say about how it is being held. Pure functions, so they can be tested. */
object GuideAdvice {
    /** Degrees the back camera is tilted from straight ahead: 0 = upright, +90 = pointing at the floor, -90 = at the ceiling. */
    fun pitchDegrees(ax: Float, ay: Float, az: Float): Float =
        Math.toDegrees(atan2(az.toDouble(), hypot(ax.toDouble(), ay.toDouble()))).toFloat()

    fun posture(pitch: Float): String? = when {
        pitch > 55f -> "The camera is pointing at the floor. Tilt the phone up, so it faces forward."
        pitch < -40f -> "The camera is pointing up. Tilt the phone down, so it faces forward."
        else -> null
    }

    fun light(lux: Float): String? =
        if (lux in 0f..4f) "It is very dark here, so I may miss things. Be extra careful." else null
}

/**
 * The accelerometer (which way the camera points), the light sensor and the proximity sensor (covered, for example in a
 * pocket). Phones have no distance sensor for obstacles, so these only tell Guide mode how well it can see.
 */
class GuideSensors(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    @Volatile var pitch = 0f
        private set
    @Volatile var lux = -1f
        private set
    @Volatile var covered = false
        private set
    @Volatile var hasAccelerometer = false
        private set

    fun start() {
        val m = manager ?: return
        m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { hasAccelerometer = m.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        m.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        m.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> pitch = GuideAdvice.pitchDegrees(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_LIGHT -> lux = e.values[0]
            Sensor.TYPE_PROXIMITY -> covered = e.values[0] < 3f && e.values[0] < e.sensor.maximumRange
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
