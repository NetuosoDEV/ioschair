/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Moves the Liquid Glass light source with device tilt, so glints slide along the rims of the
 * dock, folders and search pill exactly like iOS 26. Uses the low-power gravity sensor and only
 * runs while the launcher is in the foreground.
 */
object GlassMotion : SensorEventListener {

    // Default light: from the top-left (normalised direction the light travels).
    @Volatile
    @JvmStatic
    var lightX = 0.55f
        private set

    @Volatile
    @JvmStatic
    var lightY = 0.83f
        private set

    private var sensorManager: SensorManager? = null
    private var registered = false
    private var lastInvalidate = 0L

    @JvmStatic
    fun start(context: Context) {
        if (registered) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        sensorManager = sm
        registered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    @JvmStatic
    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        // Phone upright: gravity ~ (0, 9.8). Tilting sideways moves the light horizontally.
        val tx = (-gx / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val ty = (gy / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        var lx = 0.55f + tx * 0.9f
        var ly = 0.45f + ty * 0.45f
        val len = sqrt(lx * lx + ly * ly).coerceAtLeast(0.001f)
        lx /= len
        ly /= len
        // Low-pass filter.
        val nx = lightX + (lx - lightX) * 0.25f
        val ny = lightY + (ly - lightY) * 0.25f
        val changed = abs(nx - lightX) + abs(ny - lightY) > 0.004f
        lightX = nx
        lightY = ny
        val now = SystemClock.uptimeMillis()
        if (changed && now - lastInvalidate > 50) {
            lastInvalidate = now
            GlassBackdrop.invalidateHosts()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
