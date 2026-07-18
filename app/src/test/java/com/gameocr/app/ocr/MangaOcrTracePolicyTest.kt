package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaOcrTracePolicyTest {

    @Test
    fun sectionName_coversGlobalBubbleAndInvalidIndexCases() {
        data class Case(
            val name: String,
            val stage: MangaOcrTraceStage,
            val bubbleIndex: Int?,
            val expected: String,
        )

        listOf(
            Case("whole run", MangaOcrTraceStage.RUN, null, "MangaOCR/run"),
            Case("detector ignores index", MangaOcrTraceStage.DETECT, 12, "MangaOCR/detect"),
            Case("cluster", MangaOcrTraceStage.CLUSTER, null, "MangaOCR/cluster"),
            Case("first bubble", MangaOcrTraceStage.BUBBLE, 0, "MangaOCR/bubble[0]"),
            Case("encoder bubble", MangaOcrTraceStage.ENCODER, 13, "MangaOCR/encoder[13]"),
            Case("decoder missing index falls back", MangaOcrTraceStage.DECODER, null, "MangaOCR/decoder[0]"),
            Case("negative index clamps", MangaOcrTraceStage.BUBBLE, -4, "MangaOCR/bubble[0]"),
        ).forEach { case ->
            val actual = MangaOcrTracePolicy.sectionName(case.stage, case.bubbleIndex)
            assertEquals(case.name, case.expected, actual)
            assertTrue("${case.name} exceeds Android Trace's 127-code-unit limit", actual.length <= 127)
        }
    }
}
