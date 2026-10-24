package com.gameocr.app.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.gameocr.app.BuildConfig
import com.gameocr.app.data.Settings

/** Shares the existing foreground capture service; never claims any touchscreen events. */
internal class GestureMotionWake(
    private val context: Context,
    private val canTrigger: () -> Boolean,
    private val onTrigger: (GestureWakeMotion) -> Unit,
    private val onUnavailable: () -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val handler = Handler(Looper.getMainLooper())
    private var config: Config? = null
    private var detector = GestureMotionDetector(false, 3, false, 3)
    private var listening = false
    private var receiverRegistered = false
    private var unavailableReported = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateRegistration() }
    }
    private data class Config(val twist: Boolean, val twistLevel: Int, val tilt: Boolean, val tiltLevel: Int)

    fun configure(settings: Settings, enabled: Boolean) {
        val next = Config(enabled && settings.gestureTwistEnabled, settings.gestureTwistSensitivity,
            enabled && settings.gestureTiltEnabled, settings.gestureTiltSensitivity)
        if (config != next) {
            detector = GestureMotionDetector(next.twist, next.twistLevel, next.tilt, next.tiltLevel) {
                if (BuildConfig.DEBUG) android.util.Log.d("GestureMotionWake", it)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("GestureMotionWake", "config=$next")
            config = next
        }
        if (next.twist || next.tilt) {
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(context, screenReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
        } else {
            if (receiverRegistered) context.unregisterReceiver(screenReceiver)
            receiverRegistered = false
            unavailableReported = false
        }
        updateRegistration()
    }

    private fun updateRegistration() {
        val requested = config?.let { it.twist || it.tilt } == true
        val shouldListen = requested && power.isInteractive && !keyguard.isKeyguardLocked
        if (!shouldListen) {
            sensors.unregisterListener(this); listening = false; detector.reset()
        } else if (!listening) {
            detector.reset()
            listening = gyro?.let { sensors.registerListener(this, it, 10_000, handler) } == true
            if (BuildConfig.DEBUG) android.util.Log.d("GestureMotionWake", "sensor listening=$listening requestedPeriodUs=10000")
            if (!listening && !unavailableReported) { unavailableReported = true; onUnavailable() }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening || !canTrigger()) { detector.reset(); return }
        val factor = (180 / Math.PI).toFloat()
        val x = event.values[0] * factor
        val y = event.values[1] * factor
        val z = event.values[2] * factor
        val axes = when (display?.rotation) {
            Surface.ROTATION_90 -> y to -x
            Surface.ROTATION_180 -> -x to -y
            Surface.ROTATION_270 -> -y to x
            else -> x to y
        }
        detector.sample(axes.first, axes.second, z, event.timestamp / 1_000_000)?.let(onTrigger)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun reset() = detector.reset()

    fun close() {
        sensors.unregisterListener(this)
        if (receiverRegistered) context.unregisterReceiver(screenReceiver)
        receiverRegistered = false; listening = false; config = null; detector.reset()
    }
}
