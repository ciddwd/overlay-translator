package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaddleTextLineOrientationClassifierTest {

    @Test
    fun angleForClass_mapsPaddleTextlineLabels() {
        assertEquals(0, PaddleTextLineOrientationClassifier.angleForClass(0))
        assertEquals(180, PaddleTextLineOrientationClassifier.angleForClass(1))
        assertEquals(0, PaddleTextLineOrientationClassifier.angleForClass(-1))
        assertEquals(0, PaddleTextLineOrientationClassifier.angleForClass(99))
    }

    @Test
    fun combineLineVotes_isConservativeAndTableDriven() {
        data class Case(
            val name: String,
            val votes: List<TextLineOrientationVote>,
            val expectedAngle: Int?,
            val expectedOrientation: TextOrientation?,
        )

        val cases = listOf(
            Case(
                name = "strong 180 majority",
                votes = listOf(
                    TextLineOrientationVote(180, 0.96f),
                    TextLineOrientationVote(180, 0.90f),
                    TextLineOrientationVote(0, 0.91f),
                ),
                expectedAngle = 180,
                expectedOrientation = TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                name = "upright majority is only a keep signal",
                votes = listOf(
                    TextLineOrientationVote(0, 0.94f),
                    TextLineOrientationVote(0, 0.92f),
                    TextLineOrientationVote(180, 0.90f),
                ),
                expectedAngle = 0,
                expectedOrientation = TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                name = "low confidence ignored",
                votes = listOf(
                    TextLineOrientationVote(180, 0.84f),
                    TextLineOrientationVote(180, 0.70f),
                ),
                expectedAngle = null,
                expectedOrientation = null,
            ),
            Case(
                name = "split vote is unknown",
                votes = listOf(
                    TextLineOrientationVote(180, 0.96f),
                    TextLineOrientationVote(0, 0.96f),
                ),
                expectedAngle = null,
                expectedOrientation = null,
            ),
        )

        cases.forEach { case ->
            val result = PaddleTextLineOrientationClassifier.combineLineVotes(case.votes)
            if (case.expectedAngle == null) {
                assertNull(case.name, result)
            } else {
                requireNotNull(result) { case.name }
                assertEquals(case.name, case.expectedAngle, result.rawAngle)
                assertEquals(case.name, case.expectedOrientation, result.orientation)
                assertEquals(case.name, PaddleTextLineOrientationClassifier.SOURCE, result.source)
            }
        }
    }
}
