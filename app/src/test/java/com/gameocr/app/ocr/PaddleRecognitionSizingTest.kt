package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleRecognitionSizingTest {

    @Test
    fun resize_plan_preserves_aspect_ratio_until_official_dynamic_limit() {
        data class Case(
            val name: String,
            val cropWidth: Int,
            val cropHeight: Int,
            val expectedNaturalWidth: Int,
            val expectedTargetWidth: Int,
            val expectedCapped: Boolean,
        )

        // Expected widths computed with PaddleX OCRReisizeNormImg (int padded width, ceil content).
        val cases = listOf(
            Case("minimum width", 1, 48, 8, 8, false),
            Case("short crop keeps natural width", 100, 48, 100, 100, false),
            Case("official default width", 320, 48, 320, 320, false),
            Case("former local limit", 480, 48, 480, 480, false),
            Case("short line from failing screen", 347, 35, 475, 475, false),
            Case("long line from failing screen A", 1114, 37, 1445, 1445, false),
            Case("long line from failing screen B", 1174, 39, 1444, 1444, false),
            Case("long line from failing screen C", 1153, 36, 1537, 1537, false),
            Case("long line from failing screen D", 1036, 36, 1381, 1381, false),
            Case("official dynamic maximum", 3200, 48, 3200, 3200, false),
            Case("extreme line is bounded", 4000, 48, 4000, 3200, true),
        )

        cases.forEach { case ->
            val plan = PaddleRecognitionSizing.plan(case.cropWidth, case.cropHeight)
            assertEquals(case.name, case.expectedNaturalWidth, plan.naturalWidth)
            assertEquals(case.name, case.expectedTargetWidth, plan.targetWidth)
            assertEquals(case.name, case.expectedCapped, plan.capped)
            assertTrue(case.name, plan.targetWidth >= PaddleRecognitionSizing.MIN_WIDTH)
            assertTrue(case.name, plan.targetWidth <= PaddleRecognitionSizing.MAX_DYNAMIC_WIDTH)

            if (!plan.capped && plan.naturalWidth > PaddleRecognitionSizing.MIN_WIDTH) {
                val expectedRatio = case.cropWidth.toDouble() / case.cropHeight.toDouble()
                val actualRatio =
                    plan.targetWidth.toDouble() / PaddleRecognitionSizing.TARGET_HEIGHT.toDouble()
                assertEquals(
                    case.name,
                    expectedRatio,
                    actualRatio,
                    1.0 / PaddleRecognitionSizing.TARGET_HEIGHT,
                )
            }
        }
    }

    @Test
    fun resize_plan_rejects_invalid_crop_dimensions_tableDriven() {
        data class Case(
            val name: String,
            val cropWidth: Int,
            val cropHeight: Int,
        )

        val cases = listOf(
            Case("zero width", 0, 48),
            Case("negative width", -1, 48),
            Case("zero height", 100, 0),
            Case("negative height", 100, -1),
        )

        cases.forEach { case ->
            val error = assertThrows(case.name, IllegalArgumentException::class.java) {
                PaddleRecognitionSizing.plan(case.cropWidth, case.cropHeight)
            }
            assertFalse(case.name, error.message.isNullOrBlank())
        }
    }

    @Test
    fun tensorWidth_tableDriven_padsEveryModelToOfficialBaseWidth() {
        listOf(
            Triple("short crop", 76, 320),
            Triple("boundary", 320, 320),
            Triple("long crop", 476, 476),
            Triple("maximum", 3200, 3200),
        ).forEach { (name, contentWidth, expected) ->
            assertEquals(name, expected, PaddleRecognitionSizing.tensorWidth(contentWidth))
        }
    }

    @Test
    fun tensorWidth_rejectsInvalidWidths_tableDriven() {
        listOf(
            "below minimum" to PaddleRecognitionSizing.MIN_WIDTH - 1,
            "above maximum" to PaddleRecognitionSizing.MAX_DYNAMIC_WIDTH + 1,
        ).forEach { (name, width) ->
            val error = assertThrows(name, IllegalArgumentException::class.java) {
                PaddleRecognitionSizing.tensorWidth(width)
            }
            assertFalse(name, error.message.isNullOrBlank())
        }
    }
}
