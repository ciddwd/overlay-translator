package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTokenConfidenceTest {
    @Test
    fun `token prediction is stable across representative logit cases`() {
        data class Case(
            val name: String,
            val logits: FloatArray,
            val expectedId: Int,
            val expectedConfidence: Float,
        )

        val cases = listOf(
            Case("equal logits", floatArrayOf(0f, 0f, 0f, 0f), 0, 0.25f),
            Case("clear winner", floatArrayOf(0f, 3f, 0f), 1, 0.909443f),
            Case("large positive logits", floatArrayOf(10_000f, 9_999f), 0, 0.731059f),
            Case("large negative logits", floatArrayOf(-10_000f, -9_998f), 1, 0.880797f),
        )

        cases.forEach { case ->
            val result = mangaTokenPrediction(
                logitsByStep = arrayOf(floatArrayOf(99f), case.logits),
                calculateConfidence = true,
            )
            assertEquals(case.name, case.expectedId, result.tokenId)
            assertEquals(case.name, case.expectedConfidence, result.confidence, 0.00001f)
            assertTrue(case.name, result.confidence.isFinite())
        }
    }

    @Test
    fun `disabled confidence keeps greedy result without fabricating a score`() {
        val result = mangaTokenPrediction(
            logitsByStep = arrayOf(floatArrayOf(-1f, 2f, 1f)),
            calculateConfidence = false,
        )

        assertEquals(1, result.tokenId)
        assertTrue(result.confidence.isNaN())
    }

    @Test
    fun `sequence confidence ignores unavailable values and handles empty input`() {
        data class Case(
            val name: String,
            val values: List<Float>,
            val expectedAverage: Float,
            val expectedMinimum: Float,
            val expectedCount: Int,
        )

        val cases = listOf(
            Case("normal", listOf(0.8f, 0.6f, 1f), 0.8f, 0.6f, 3),
            Case("unavailable ignored", listOf(Float.NaN, 0.5f), 0.5f, 0.5f, 1),
            Case("empty", emptyList(), Float.NaN, Float.NaN, 0),
        )

        cases.forEach { case ->
            val result = mangaSequenceConfidence(case.values)
            assertEquals(case.name, case.expectedCount, result.tokenCount)
            if (case.expectedAverage.isNaN()) {
                assertTrue(case.name, result.average.isNaN())
                assertTrue(case.name, result.minimum.isNaN())
            } else {
                assertEquals(case.name, case.expectedAverage, result.average, 0.00001f)
                assertEquals(case.name, case.expectedMinimum, result.minimum, 0.00001f)
            }
        }
    }
}
