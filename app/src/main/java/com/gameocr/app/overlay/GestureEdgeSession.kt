package com.gameocr.app.overlay

import kotlin.math.hypot

/** Entry motion never becomes a gesture. A pause establishes a new drawing origin. */
internal class GestureEdgeSession(point: GesturePoint, now: Long, private val density: Float) {
    enum class Phase { WAITING, READY, DRAWING }
    var phase = Phase.WAITING
        private set
    var anchor = point
        private set
    var lastMovementAt = now
        private set
    var marker = point
        private set

    fun move(point: GesturePoint, now: Long) {
        marker = point
        when (phase) {
            Phase.WAITING -> if (distance(point, anchor) > 6 * density) {
                anchor = point
                lastMovementAt = now
            }
            Phase.READY -> if (distance(point, anchor) >= 8 * density) phase = Phase.DRAWING
            Phase.DRAWING -> Unit
        }
    }

    fun pause(now: Long) {
        if (phase == Phase.WAITING && now - lastMovementAt >= 240) {
            anchor = marker
            phase = Phase.READY
        }
    }

    private fun distance(a: GesturePoint, b: GesturePoint) = hypot(a.x - b.x, a.y - b.y)
}
