package com.gameocr.app.overlay

import com.gameocr.app.data.GestureMotionSensitivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

internal enum class GestureWakeMotion { TWIST, TILT }

/** Two small excursions and returns, without requiring repeated full swings past the origin. */
internal class GestureMotionDetector(
    private val twistEnabled: Boolean,
    twistSensitivity: Int,
    private val tiltEnabled: Boolean,
    tiltSensitivity: Int,
    private val onTrace: (String) -> Unit = {},
) {
    private val twist = Sequence(GestureMotionSensitivity.degrees(twistSensitivity))
    private val screenTurn = Sequence(GestureMotionSensitivity.degrees(twistSensitivity))
    private val tilt = Sequence(GestureMotionSensitivity.degrees(tiltSensitivity))
    private var lastAt: Long? = null
    private var lastPitchRate = 0f
    private var lastTwistRate = 0f
    private var lastRollRate = 0f
    private var pitch = 0f
    private var yaw = 0f
    private var roll = 0f
    private var peakPitch = 0f
    private var peakYaw = 0f
    private var peakRoll = 0f
    private var movementAt: Long? = null
    private var quietAt: Long? = null
    private var cooldownUntil = 0L
    private var waitForStill = false

    /** Rates are degrees per second, mapped to the current screen axes by the sensor owner. */
    fun sample(pitchRate: Float, twistRate: Float, rollRate: Float, now: Long): GestureWakeMotion? {
        if (!pitchRate.isFinite() || !twistRate.isFinite() || !rollRate.isFinite()) { reset("invalid"); return null }
        if (now < cooldownUntil) { reset(); return null }
        val speed = max(max(abs(pitchRate), abs(twistRate)), abs(rollRate))
        if (waitForStill) {
            if (speed >= 3f) quietAt = null
            else {
                if (quietAt == null) quietAt = now
                if (now - quietAt!! >= 400) { waitForStill = false; reset() }
            }
            return null
        }
        val previous = lastAt
        lastAt = now
        if (previous == null || now - previous !in 1..250) {
            if (previous != null) { reset("sample gap"); lastAt = now }
            lastPitchRate = pitchRate; lastTwistRate = twistRate; lastRollRate = rollRate
            return null
        }
        val dt = (now - previous) / 1000f
        pitch += (lastPitchRate + pitchRate) * .5f * dt
        yaw += (lastTwistRate + twistRate) * .5f * dt
        roll += (lastRollRate + rollRate) * .5f * dt
        lastPitchRate = pitchRate; lastTwistRate = twistRate; lastRollRate = rollRate
        peakPitch = max(peakPitch, abs(pitch)); peakYaw = max(peakYaw, abs(yaw)); peakRoll = max(peakRoll, abs(roll))
        // Large display-orientation changes are not the small wrist gesture.
        if (abs(roll) > 55f || max(abs(pitch), abs(yaw)) > 65f) {
            reset("large rotation"); waitForStill = true; return null
        }
        if (speed < 3f) {
            if (quietAt == null) quietAt = now
            val hasProgress = twist.hasProgress || tilt.hasProgress || screenTurn.hasProgress
            if (now - quietAt!! >= if (hasProgress) 1200 else 600) { reset("rest"); return null }
        } else quietAt = null
        if (movementAt == null && max(max(abs(pitch), abs(yaw)), abs(roll)) > 1.5f) movementAt = now
        if (movementAt?.let { now - it > 4200 } == true) { reset("timeout"); return null }

        val yawDone = twistEnabled && twist.update(yaw, max(abs(pitch), abs(roll)), now)
        val rollDone = twistEnabled && screenTurn.update(roll, max(abs(pitch), abs(yaw)), now)
        val tiltDone = tiltEnabled && tilt.update(pitch, max(abs(yaw), abs(roll)), now)
        val detected = when {
            (yawDone || rollDone) && (!tiltDone || max(peakYaw, peakRoll) >= peakPitch) -> GestureWakeMotion.TWIST
            tiltDone -> GestureWakeMotion.TILT
            else -> null
        }
        if (detected != null) {
            onTrace("recognized=$detected peakDeg(pitch/yaw/roll)=${peakPitch.toInt()}/${peakYaw.toInt()}/${peakRoll.toInt()}")
            cooldownUntil = now + 1400
            reset("recognized")
        }
        return detected
    }

    /** Preserve the cooldown across capture, drawing and service state changes. */
    fun reset(reason: String = "inactive") {
        if (movementAt != null) onTrace("reset=$reason peakDeg(pitch/yaw/roll)=${peakPitch.toInt()}/${peakYaw.toInt()}/${peakRoll.toInt()} returns=${tilt.returns}/${twist.returns}/${screenTurn.returns}")
        lastAt = null; pitch = 0f; yaw = 0f; roll = 0f; movementAt = null; quietAt = null
        peakPitch = 0f; peakYaw = 0f; peakRoll = 0f
        lastPitchRate = 0f; lastTwistRate = 0f; lastRollRate = 0f
        twist.reset(); tilt.reset(); screenTurn.reset()
    }

    private class Sequence(private val threshold: Float) {
        var returns = 0
            private set
        val hasProgress get() = returning || returns > 0
        private var returning = false
        private var direction = 1f
        private var peak = 0f
        private var peakAt = 0L
        private var lastReturnAt = 0L
        fun reset() { returns = 0; returning = false; peak = 0f; peakAt = 0L; lastReturnAt = 0L }
        fun update(angle: Float, other: Float, now: Long): Boolean {
            if (!returning) {
                // Select the main axis at the start; small off-axis drift during the return is allowed.
                if (abs(angle) < threshold || abs(angle) < other * .75f || now - lastReturnAt < 40) return false
                direction = sign(angle); peak = abs(angle); peakAt = now; returning = true
            } else {
                val directed = angle * direction
                if (directed > peak) { peak = directed; peakAt = now }
                val returned = abs(angle) <= threshold * .9f && peak - directed >= threshold * .55f
                if (returned && now - peakAt >= 20) {
                    returns++; returning = false; lastReturnAt = now
                    return returns == 2
                }
            }
            return false
        }
    }
}
