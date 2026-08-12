package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VerticalTextPreservationPolicyTest {

    @Test
    fun routingPipeline_hasNoGeometryBasedFuriganaDeletion() {
        val source = routingSource().readText()
        data class Case(val name: String, val removedMarker: String)

        listOf(
            Case("legacy filter function", "removeFurigana("),
            Case("legacy removal log", "[V] removeFurigana"),
            Case("legacy width ratio threshold", "sb.width().toFloat() / bb.width()"),
            Case("legacy height ratio threshold", "sb.height().toFloat() / bb.height()"),
        ).forEach { case ->
            assertFalse(case.name, source.contains(case.removedMarker))
        }
        org.junit.Assert.assertTrue(
            "Manga OCR free text may use user-requested geometric merging without deletion",
            source.contains("mergeStandaloneFreeText = kind == OcrEngineKind.MANGA_OCR_JA"),
        )
    }

    @Test
    fun preservedVerticalColumns_continueThroughNormalMergeDecision() {
        data class Case(
            val name: String,
            val first: MergeDebugRect,
            val second: MergeDebugRect,
            val expectedMergeAllowed: Boolean,
        )

        val cases = listOf(
            Case(
                name = "logcat regression keeps narrow right sentence column",
                first = MergeDebugRect(249, 480, 329, 827),
                second = MergeDebugRect(104, 480, 247, 970),
                expectedMergeAllowed = true,
            ),
            Case(
                name = "small ruby-like column is preserved for normal grouping",
                first = MergeDebugRect(163, 50, 190, 300),
                second = MergeDebugRect(100, 0, 160, 400),
                expectedMergeAllowed = true,
            ),
            Case(
                name = "equal width adjacent columns still merge",
                first = MergeDebugRect(100, 10, 150, 300),
                second = MergeDebugRect(45, 20, 95, 310),
                expectedMergeAllowed = true,
            ),
            Case(
                name = "distant narrow note stays as a separate preserved block",
                first = MergeDebugRect(260, 50, 290, 300),
                second = MergeDebugRect(100, 0, 160, 400),
                expectedMergeAllowed = false,
            ),
            Case(
                name = "non-overlapping narrow note stays as a separate preserved block",
                first = MergeDebugRect(163, 430, 190, 600),
                second = MergeDebugRect(100, 0, 160, 400),
                expectedMergeAllowed = false,
            ),
        )

        cases.forEach { case ->
            val limits = verticalColumnMergeLimits(
                rects = listOf(case.first, case.second),
                verticalGapRatio = 0.8f,
            )
            val actual = verticalColumnMergeAllowed(
                debug = verticalColumnAdjacencyDebug(case.first, case.second),
                limits = limits,
                horizontalOverlapRatio = 0.3f,
            )
            assertEquals(case.name, case.expectedMergeAllowed, actual)
        }
    }

    private fun routingSource(): File = listOf(
        File("src/main/java/com/gameocr/app/ocr/RoutingOcrEngine.kt"),
        File("app/src/main/java/com/gameocr/app/ocr/RoutingOcrEngine.kt"),
    ).firstOrNull(File::isFile) ?: error("RoutingOcrEngine.kt not found")
}
