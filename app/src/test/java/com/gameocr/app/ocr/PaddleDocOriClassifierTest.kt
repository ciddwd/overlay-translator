package com.gameocr.app.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleDocOriClassifierTest {

    @Test
    fun orientationForAngle_isConservativeForDocRotationLabels() {
        data class Case(
            val angle: Int,
            val confidence: Float,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case(0, 0.99f, TextOrientation.UNKNOWN),
            Case(180, 0.99f, TextOrientation.UNKNOWN),
            Case(90, 0.80f, TextOrientation.VERTICAL_RTL),
            Case(270, 0.80f, TextOrientation.VERTICAL_RTL),
            Case(90, PaddleDocOriClassifier.MIN_VERTICAL_CONFIDENCE - 0.01f, TextOrientation.UNKNOWN),
            Case(-90, 0.80f, TextOrientation.VERTICAL_RTL),
            Case(450, 0.80f, TextOrientation.VERTICAL_RTL),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                PaddleDocOriClassifier.orientationForAngle(case.angle, case.confidence)
            )
        }
    }

    @Test
    fun softmax_isStableAndNormalizes() {
        val probabilities = PaddleDocOriClassifier.softmax(floatArrayOf(1000f, 1001f, 999f, 998f))

        assertEquals(4, probabilities.size)
        assertEquals(1, probabilities.indices.maxByOrNull { probabilities[it] })
        assertTrue(probabilities.all { it in 0f..1f })
        assertEquals(1f, probabilities.sum(), 0.0001f)
    }

    @Test
    fun extractScores_handlesOnnxRuntimeCommonShapes() {
        data class Case(
            val name: String,
            val value: Any?,
            val expected: FloatArray,
        )

        val cases = listOf(
            Case("flat", floatArrayOf(1f, 2f, 3f, 4f), floatArrayOf(1f, 2f, 3f, 4f)),
            Case("batch", arrayOf(floatArrayOf(4f, 3f, 2f, 1f)), floatArrayOf(4f, 3f, 2f, 1f)),
            Case(
                "nested",
                arrayOf(arrayOf(floatArrayOf(0f, 1f, 0f, 0f))),
                floatArrayOf(0f, 1f, 0f, 0f)
            ),
            Case("unsupported", "bad", FloatArray(0)),
        )

        cases.forEach { case ->
            assertArrayEquals(
                case.name,
                case.expected,
                PaddleDocOriClassifier.extractScores(case.value),
                0.0001f
            )
        }
    }
}
