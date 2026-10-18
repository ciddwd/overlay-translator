package com.gameocr.app.overlay

import com.gameocr.app.data.PresetGesture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetGestureRecognizerTest {
    private fun path(vararg xy: Int) = xy.toList().chunked(2).map { GesturePoint(it[0].toFloat(), it[1].toFloat()) }

    private val examples = mapOf(
        PresetGesture.UP to path(20, 220, 23, 160, 19, 90, 20, 20),
        PresetGesture.DOWN to path(20, 20, 23, 90, 19, 160, 20, 220),
        PresetGesture.LEFT to path(220, 20, 160, 23, 90, 19, 20, 20),
        PresetGesture.RIGHT to path(20, 20, 90, 23, 160, 19, 220, 20),
        PresetGesture.RIGHT_DOWN to path(0, 0, 60, 2, 140, 0, 150, 10, 152, 90, 150, 200),
        PresetGesture.DOWN_RIGHT to path(0, 0, 2, 60, 0, 190, 10, 200, 70, 202, 150, 200),
        PresetGesture.S to path(160, 25, 130, 5, 65, 0, 15, 25, 5, 55, 25, 83,
            80, 100, 130, 118, 163, 148, 150, 180, 110, 200, 50, 198, 10, 175),
        PresetGesture.O to List(85) { i ->
            val angle = PI / 11 + i * 2 * PI / 84
            GesturePoint((90 * cos(angle)).toFloat(), (120 * sin(angle)).toFloat())
        },
    )

    @Test fun circlesAndHorizontalOrVerticalEllipsesAcceptEitherDirectionAndAnyOrigin() {
        for ((rx,ry) in listOf(100f to 100f,65f to 100f,100f to 65f,75f to 100f)) {
            for (start in listOf(0.0, PI/7, PI/2)) for (direction in listOf(-1,1)) {
                val points = List(97) { i ->
                    val angle = start + direction*i*2*PI/96
                    GesturePoint(300+(rx*cos(angle)).toFloat(),500+(ry*sin(angle)).toFloat())
                }
                assertEquals("radii=$rx,$ry start=$start direction=$direction", PresetGesture.O,
                    PresetGestureRecognizer.recognize(points,40f))
            }
        }
    }

    @Test fun eightGesturesRecognizeAcrossPositionsAndSizes() {
        for ((gesture, points) in examples) for (scale in listOf(0.4f, 1f, 3f)) {
            val moved = points.map { GesturePoint(700 + it.x * scale, 300 + it.y * scale) }
            assertEquals("$gesture scale=$scale", gesture, PresetGestureRecognizer.recognize(moved, 30f))
        }
    }

    @Test fun strokeSpeedAndRepeatedCoordinatesDoNotChangeTheResult() {
        examples.forEach { (gesture, points) ->
            val pauses = points.flatMapIndexed { index, point -> List(index % 4 + 1) { point } }
            assertEquals(gesture, PresetGestureRecognizer.recognize(pauses, 30f))
        }
    }

    @Test fun sAndOAcceptEitherDrawingDirection() {
        for (gesture in listOf(PresetGesture.S, PresetGesture.O)) {
            assertEquals(gesture, PresetGestureRecognizer.recognize(examples.getValue(gesture).reversed(), 30f))
        }
    }

    @Test fun cornersPreserveTheirOrderAndDirection() {
        assertNull(PresetGestureRecognizer.recognize(examples.getValue(PresetGesture.RIGHT_DOWN).reversed(), 30f))
        assertNull(PresetGestureRecognizer.recognize(examples.getValue(PresetGesture.DOWN_RIGHT).reversed(), 30f))
    }

    @Test fun tapsTinyStrokesDiagonalsAndOtherShapesDoNotFire() {
        val rejected = listOf(
            emptyList(), path(20, 20), path(20, 20, 20, 20), path(20, 20, 23, 22),
            path(0, 0, 200, 200), path(0, 0, 100, 200, 200, 0),
            path(0, 0, 200, 0, 0, 200, 200, 200),
            path(0, 0, 100, 200, 200, 0, 0, 0),
        )
        rejected.forEach { assertNull("Unexpected match for $it", PresetGestureRecognizer.recognize(it, 30f)) }
        assertNull(PresetGestureRecognizer.recognize(listOf(GesturePoint(Float.NaN, 1f), GesturePoint(2f, 3f)), 30f))
    }
}
