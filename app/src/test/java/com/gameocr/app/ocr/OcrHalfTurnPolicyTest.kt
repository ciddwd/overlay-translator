package com.gameocr.app.ocr

import com.gameocr.app.capture.CaptureContentOrientationPolicy
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class OcrHalfTurnPolicyTest {
    private fun pair(current: Float, opposite: Float, text: String = "recognized words") =
        HalfTurnReadingPair(HalfTurnReading(text, current), HalfTurnReading(text, opposite))

    private val capturedGameLines = listOf(
        HalfTurnReadingPair(HalfTurnReading("(1/0)", 0.6512f),
            HalfTurnReading("테살리아 평원에서 재앙의 토템", 0.9209f)),
        HalfTurnReadingPair(HalfTurnReading("x [09o]", 0.6991f),
            HalfTurnReading("에픽60 황금 들판의 탐식자", 0.8590f)),
        HalfTurnReadingPair(HalfTurnReading("(군이)이이", 0.6264f),
            HalfTurnReading("길드레이드 1회 클리어", 0.9290f)),
        HalfTurnReadingPair(HalfTurnReading("[]", 0.6381f),
            HalfTurnReading("서브 길드레이드", 0.9323f)),
    )

    @Test
    fun actualGameReadings_chooseTheReadableSideInBothDirections() {
        listOf(false, true).forEach { reverse ->
            val readings = if (reverse) capturedGameLines.map { HalfTurnReadingPair(it.opposite, it.current) }
                else capturedGameLines
            val result = requireNotNull(OcrHalfTurnPolicy.decide(readings))
            assertEquals(if (reverse) 0 else 180, result.rawAngle)
            assertEquals(0.9103f, result.confidence, 0.0001f)
            assertEquals(OcrHalfTurnPolicy.SOURCE, result.source)
        }
    }

    @Test
    fun decision_requiresMultipleClearTextReadings_tableDriven() {
        data class Case(val name: String, val pairs: List<HalfTurnReadingPair>, val angle: Int?)
        val upsideDown = pair(0.5f, 0.95f)
        val upright = pair(0.95f, 0.5f)
        listOf(
            Case("empty", emptyList(), null),
            Case("one line cannot turn a frame", listOf(upsideDown), null),
            Case("two clear lines", listOf(upsideDown, upsideDown), 180),
            Case("upright frame", listOf(upright, upright), 0),
            Case("three of four clear lines", listOf(upsideDown, upright, upsideDown, upsideDown), 180),
            Case("split", listOf(upsideDown, upsideDown, upright, upright), null),
            Case("small confidence difference", List(4) { pair(0.88f, 0.92f) }, null),
            Case("below minimum confidence", List(4) { pair(0.4f, 0.79f) }, null),
            Case("confidence gain boundary", List(2) { pair(0.8f, 0.9f) }, 180),
            Case("confidence boundary", List(2) { pair(0.6f, 0.8f) }, 180),
            Case("numbers do not indicate direction", List(4) { pair(0.5f, 0.99f, "1008 / 69") }, null),
            Case("short fragments do not indicate direction", List(4) { pair(0.5f, 0.99f, "ab") }, null),
            Case("invalid confidence", listOf(pair(Float.NaN, 0.99f), pair(0.2f, 1.01f),
                pair(0.2f, Float.POSITIVE_INFINITY)), null),
        ).forEach { case ->
            assertEquals(case.name, case.angle, OcrHalfTurnPolicy.decide(case.pairs)?.rawAngle)
        }
    }

    @Test
    fun candidateGeometry_rejectsIconsAndVerticalColumns_tableDriven() {
        listOf(Triple(12, 8, true), Triple(11, 8, false), Triple(100, 7, false),
            Triple(50, 50, false), Triple(0, 10, false), Triple(20, 200, false),
            Triple(600, 60, true)).forEach { (w, h, expected) ->
            assertEquals("$w x $h", expected, OcrHalfTurnPolicy.isCandidate(w, h))
        }
    }

    @Test
    fun correctedRotation_handlesBothSidesAndNormalizedAngles_tableDriven() {
        listOf(0 to 180, 90 to 270, 180 to 0, 270 to 90, -90 to 90,
            -450 to 90, 450 to 270).forEach { (input, expected) ->
            assertEquals("input=$input", expected, OcrHalfTurnPolicy.correctedRotation(input))
        }
    }

    @Test
    fun failedDocumentGuess_isReplacedForTextCoordinatesMasksAndPresentation() {
        val evidence = requireNotNull(OcrHalfTurnPolicy.decide(capturedGameLines))
        assertEquals(180, evidence.rawAngle)
        val verifiedRotation = OcrHalfTurnPolicy.correctedRotation(270)
        assertEquals(90, verifiedRotation)
        val recognized = IntRect(20, 10, 40, 30)
        val display = CaptureContentOrientationPolicy.mapIntRectToOriginal(recognized, 100, 80, verifiedRotation)
        assertEquals(IntRect(70, 20, 90, 40), display)
        val mask = CaptureContentOrientationPolicy.mapIntRectToOcr(display, 100, 80, verifiedRotation)
        assertEquals(recognized, mask)
    }
}
