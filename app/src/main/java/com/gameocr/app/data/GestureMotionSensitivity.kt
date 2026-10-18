package com.gameocr.app.data

object GestureMotionSensitivity {
    // The same five amplitudes shown by the concept's phone animations.
    fun degrees(level: Int): Float = when (level.coerceIn(1, 5)) {
        1 -> 28f
        2 -> 21f
        3 -> 14f
        4 -> 9f
        else -> 5f
    }
}
