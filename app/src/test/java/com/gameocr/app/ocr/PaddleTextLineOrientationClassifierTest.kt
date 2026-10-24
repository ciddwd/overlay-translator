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

    /** Official input is one detected line per crop, exactly at the detector box (no padding). */
    @Test
    fun selectLineCrops_usesIndividualLinesUnpaddedAndClamped_tableDriven() {
        fun box(l: Int, t: Int, r: Int, b: Int) = TextLineCropBounds(l, t, r, b)
        data class Case(
            val name: String,
            val boxes: List<TextLineCropBounds>,
            val expected: List<TextLineCropBounds>,
        )
        listOf(
            Case(
                "members of a merged paragraph are separate crops, largest first",
                listOf(box(10, 10, 210, 40), box(10, 50, 310, 80)),
                listOf(box(10, 50, 310, 80), box(10, 10, 210, 40)),
            ),
            Case(
                "box is not padded",
                listOf(box(100, 100, 200, 120)),
                listOf(box(100, 100, 200, 120)),
            ),
            Case(
                "clamped to the bitmap",
                listOf(box(-20, -5, 120, 30)),
                listOf(box(0, 0, 120, 30)),
            ),
            Case(
                "vertical and tiny boxes are skipped, duplicates collapse",
                listOf(box(0, 0, 20, 200), box(0, 0, 7, 3), box(50, 50, 150, 70), box(50, 50, 150, 70)),
                listOf(box(50, 50, 150, 70)),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                PaddleTextLineOrientationClassifier.selectLineCrops(case.boxes, imageWidth = 1000, imageHeight = 800),
            )
        }

        val many = (0 until 20).map { box(0, it * 40, 100 + it, it * 40 + 20) }
        assertEquals(
            PaddleTextLineOrientationClassifier.MAX_LINES,
            PaddleTextLineOrientationClassifier.selectLineCrops(many, 1000, 1000).size,
        )
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
            Case("empty votes", emptyList(), null, null),
            Case("one upside down line cannot flip the frame", listOf(TextLineOrientationVote(180, 0.99f)), null, null),
            Case("two strong upside down lines", listOf(TextLineOrientationVote(180, 0.99f),
                TextLineOrientationVote(180, 0.95f)), 180, TextOrientation.HORIZONTAL_LTR),
            Case("exact confidence and majority boundary", listOf(TextLineOrientationVote(180, 0.85f),
                TextLineOrientationVote(180, 0.85f), TextLineOrientationVote(180, 0.85f),
                TextLineOrientationVote(0, 0.85f), TextLineOrientationVote(0, 0.85f)),
                180, TextOrientation.HORIZONTAL_LTR),
            Case("invalid votes cannot flip the frame", listOf(TextLineOrientationVote(180, Float.NaN),
                TextLineOrientationVote(180, 1.01f), TextLineOrientationVote(90, 0.99f)), null, null),
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

    @Test
    fun bundledModelProbabilities_canActuallyTriggerTheUpsideDownCorrection() {
        val votes = listOf(floatArrayOf(0.02f, 0.98f), floatArrayOf(0.06f, 0.94f),
            floatArrayOf(0.97f, 0.03f)).map { scores ->
            val probabilities = PaddleDocOriClassifier.probabilitiesFromScores(scores)
            val best = probabilities.indices.maxBy { probabilities[it] }
            TextLineOrientationVote(PaddleTextLineOrientationClassifier.angleForClass(best), probabilities[best])
        }
        val result = requireNotNull(PaddleTextLineOrientationClassifier.combineLineVotes(votes))
        assertEquals(180, result.rawAngle)
        assertEquals(0.96f, result.confidence, 0.0001f)
    }
}
