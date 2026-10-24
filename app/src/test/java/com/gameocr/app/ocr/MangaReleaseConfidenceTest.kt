package com.gameocr.app.ocr

import android.graphics.Rect
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MangaReleaseConfidenceTest {
    @Test fun productionDecoders_doNotDependOnTheDebugFlagForFunctionalConfidence() {
        val path = "src/main/java/com/gameocr/app/ocr/MangaOcrEngine.kt"
        val source = listOf(File(path), File("app", path)).first { it.isFile }.readText()
        val confidenceArguments = Regex("calculateConfidence\\s*=\\s*([^,\\r\\n]+)")
            .findAll(source).map { it.groupValues[1].trim() }.toList()
        assertTrue("production decoder paths must supply confidence", confidenceArguments.isNotEmpty())
        confidenceArguments.forEach { argument ->
            assertEquals("Release and Debug must both compute scores consumed by the quality gate", "true", argument)
        }
    }

    @Test fun speculativeReferenceConfidence_keepsValidTextAndStillRejectsUnreliableText() {
        data class Case(val name: String, val margin: Float, val blocked: Boolean)
        for (case in listOf(Case("clear text", 12f, false), Case("uncertain text", 0.01f, true))) {
            val decoder = MangaSpeculativeDecoding.Decoder { rows, _, lengths ->
                Array(rows.size) { row ->
                    Array(lengths.max()) { position ->
                        val next = if (position < 3) 10 + position else 3
                        FloatArray(32) { token -> if (token == next) case.margin else 0f }
                    }
                }
            }
            val result = MangaSpeculativeDecoding(
                maxTokens = 8, startTokenId = 2, endTokenId = 3, calculateConfidence = true,
            ).decode(rowCount = 1, draft = decoder, reference = decoder)
            assertArrayEquals(case.name, intArrayOf(2, 10, 11, 12), result.ids.single())
            val confidence = mangaSequenceConfidence(
                (1 until result.ids.single().size).map { result.confidences.single()[it] },
            )
            assertEquals(case.name, 3, confidence.tokenCount)
            assertTrue(case.name, confidence.average.isFinite())
            val blocks = listOf(TextBlock(
                text = "正常な日本語の文章です", boundingBox = Rect(0, 0, 100, 100),
                confidence = confidence.average, recognizedLanguage = "ja",
            ))
            assertEquals(case.name, case.blocked, findOcrResultQualityIssue(blocks) != null)
        }
    }
}
