package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrResultQualityTest {

    @Test
    fun findOcrResultQualityIssue_tableDriven() {
        data class Case(
            val name: String,
            val blocks: List<TextBlock>,
            val expectedIssue: Boolean
        )

        val cases = listOf(
            Case(
                name = "umi-logcat-low-confidence-noise-result-blocked",
                blocks = listOf(
                    block("\u6D66\u5340\n\u50F9\nTI\n\u7528 \u65E5", 0.211f),
                    block("I", 0.290f),
                    block("1", 0.145f)
                ),
                expectedIssue = true
            ),
            Case(
                name = "umi-logcat-after-ascii-filter-still-blocked",
                blocks = listOf(
                    block("\u6D66\u5340\n\u50F9\n\u7528\n\u65E5", 0.211f)
                ),
                expectedIssue = true
            ),
            Case(
                name = "good-vertical-umi-result-kept",
                blocks = listOf(
                    block("\u6211\u6240\u5728\u7684\u8AA0\u5357\u9AD8\u4E2D", 0.919f),
                    block("\u800C\u7D66\u4E88\u6211\u5011\u52D5\u529B", 0.977f)
                ),
                expectedIssue = false
            ),
            Case(
                name = "one-low-confidence-block-among-good-blocks-kept",
                blocks = listOf(
                    block("\u6211\u6240\u5728\u7684\u8AA0\u5357\u9AD8\u4E2D", 0.920f),
                    block("I", 0.200f),
                    block("\u800C\u7D66\u4E88\u6211\u5011\u52D5\u529B", 0.940f)
                ),
                expectedIssue = false
            ),
            Case(
                name = "default-confidence-engines-kept",
                blocks = listOf(
                    block("HELLO", 1.0f),
                    block("\u6B63\u5E38\u6587\u672C", 1.0f)
                ),
                expectedIssue = false
            )
        )

        cases.forEach { case ->
            val issue = findOcrResultQualityIssue(case.blocks)
            if (case.expectedIssue) {
                assertTrue(case.name, issue != null)
            } else {
                assertFalse(case.name, issue != null)
            }
        }
    }

    private fun block(text: String, confidence: Float): TextBlock =
        TextBlock(
            text = text,
            boundingBox = Rect(0, 0, 100, 100),
            confidence = confidence,
            recognizedLanguage = "zh-TW"
        )
}
