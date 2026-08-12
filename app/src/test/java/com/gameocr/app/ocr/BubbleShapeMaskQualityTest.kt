package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleShapeMaskQualityTest {

    @Test
    fun fromDetectorDecision_tableDriven_routesOnlyMeasuredShapesToShapePatches() {
        data class Case(
            val name: String,
            val accepted: Boolean,
            val reason: String,
            val expectedQuality: BubbleShapeMaskQuality,
            val expectedShapePatch: Boolean,
            val expectedRejectionReason: String?,
        )

        val cases = listOf(
            Case(
                name = "measured interior",
                accepted = true,
                reason = "accepted",
                expectedQuality = BubbleShapeMaskQuality.TRUSTED,
                expectedShapePatch = true,
                expectedRejectionReason = null,
            ),
            Case(
                name = "measured edge bounded interior",
                accepted = true,
                reason = "accepted_edge",
                expectedQuality = BubbleShapeMaskQuality.TRUSTED,
                expectedShapePatch = true,
                expectedRejectionReason = null,
            ),
            Case(
                name = "measured expanded roi interior",
                accepted = true,
                reason = "accepted_after_roi_expand",
                expectedQuality = BubbleShapeMaskQuality.TRUSTED,
                expectedShapePatch = true,
                expectedRejectionReason = null,
            ),
            Case(
                name = "synthetic ellipse is text repair only",
                accepted = true,
                reason = "accepted_ellipse_fallback",
                expectedQuality = BubbleShapeMaskQuality.APPROXIMATE,
                expectedShapePatch = false,
                expectedRejectionReason = "APPROXIMATE_MODEL_MASK",
            ),
            Case(
                name = "rejected detector never becomes a shape",
                accepted = false,
                reason = "member_coverage_low",
                expectedQuality = BubbleShapeMaskQuality.REJECTED,
                expectedShapePatch = false,
                expectedRejectionReason = "REJECTED_MODEL_MASK",
            ),
            Case(
                name = "rejected ellipse reason stays rejected",
                accepted = false,
                reason = "accepted_ellipse_fallback",
                expectedQuality = BubbleShapeMaskQuality.REJECTED,
                expectedShapePatch = false,
                expectedRejectionReason = "REJECTED_MODEL_MASK",
            ),
        )

        cases.forEach { case ->
            val quality = BubbleShapeMaskQuality.fromDetectorDecision(
                accepted = case.accepted,
                reason = case.reason,
            )
            assertEquals("${case.name} quality", case.expectedQuality, quality)
            assertEquals("${case.name} route", case.expectedShapePatch, quality.supportsShapePatch)
            assertEquals(
                "${case.name} rejection",
                case.expectedRejectionReason,
                quality.shapePatchRejectionReason,
            )
        }
    }
}
