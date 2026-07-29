package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaBubbleDetectionPostprocessorTest {
    @Test
    fun process_tableDriven_filtersClassesConfidenceAndInvalidBoxes() {
        data class Case(
            val name: String,
            val label: Long,
            val score: Float,
            val box: FloatArray,
            val expected: MangaBubbleDetectionPostprocessor.Detection?,
        )
        val cases = listOf(
            Case(
                name = "valid bubble",
                label = 0,
                score = 0.82f,
                box = floatArrayOf(10f, 20f, 90f, 80f),
                expected = MangaBubbleDetectionPostprocessor.Detection(
                    confidence = 0.82f,
                    left = 10f,
                    top = 20f,
                    right = 90f,
                    bottom = 80f,
                ),
            ),
            Case(
                name = "text inside bubble class",
                label = 1,
                score = 0.99f,
                box = floatArrayOf(10f, 20f, 90f, 80f),
                expected = null,
            ),
            Case(
                name = "free text class",
                label = 2,
                score = 0.99f,
                box = floatArrayOf(10f, 20f, 90f, 80f),
                expected = null,
            ),
            Case(
                name = "below confidence",
                label = 0,
                score = 0.29f,
                box = floatArrayOf(10f, 20f, 90f, 80f),
                expected = null,
            ),
            Case(
                name = "coordinates clipped to image",
                label = 0,
                score = 0.71f,
                box = floatArrayOf(-5f, -8f, 130f, 110f),
                expected = MangaBubbleDetectionPostprocessor.Detection(
                    confidence = 0.71f,
                    left = 0f,
                    top = 0f,
                    right = 120f,
                    bottom = 100f,
                ),
            ),
            Case(
                name = "inverted box",
                label = 0,
                score = 0.90f,
                box = floatArrayOf(90f, 80f, 10f, 20f),
                expected = null,
            ),
            Case(
                name = "non finite score",
                label = 0,
                score = Float.NaN,
                box = floatArrayOf(10f, 20f, 90f, 80f),
                expected = null,
            ),
            Case(
                name = "short output row",
                label = 0,
                score = 0.90f,
                box = floatArrayOf(10f, 20f, 90f),
                expected = null,
            ),
        )

        cases.forEach { case ->
            val result = MangaBubbleDetectionPostprocessor.process(
                imageWidth = 120,
                imageHeight = 100,
                labels = longArrayOf(case.label),
                boxes = arrayOf(case.box),
                scores = floatArrayOf(case.score),
            )
            assertEquals(case.name, listOfNotNull(case.expected), result)
        }
    }

    @Test
    fun process_tableDriven_usesShortestOutputAndSortsByConfidence() {
        val result = MangaBubbleDetectionPostprocessor.process(
            imageWidth = 200,
            imageHeight = 160,
            labels = longArrayOf(0, 0, 0),
            boxes = arrayOf(
                floatArrayOf(10f, 10f, 50f, 50f),
                floatArrayOf(60f, 60f, 100f, 100f),
            ),
            scores = floatArrayOf(0.45f, 0.91f, 0.99f),
        )

        assertEquals(2, result.size)
        assertTrue(result[0].confidence > result[1].confidence)
        assertEquals(60f, result[0].left)
    }

    @Test
    fun processAll_tableDriven_preservesSupportedModelClasses() {
        data class Case(
            val name: String,
            val label: Long,
            val expectedKind: MangaBubbleDetectionPostprocessor.Kind?,
        )
        val cases = listOf(
            Case("bubble", 0L, MangaBubbleDetectionPostprocessor.Kind.BUBBLE),
            Case("text in bubble", 1L, MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE),
            Case("free text", 2L, MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE),
            Case("unknown model class", 99L, null),
        )

        cases.forEach { case ->
            val result = MangaBubbleDetectionPostprocessor.processAll(
                imageWidth = 120,
                imageHeight = 100,
                labels = longArrayOf(case.label),
                boxes = arrayOf(floatArrayOf(10f, 20f, 90f, 80f)),
                scores = floatArrayOf(0.9f),
            )

            assertEquals(case.name, listOfNotNull(case.expectedKind), result.map { it.kind })
        }
    }

    @Test
    fun processAll_nearDuplicateCollapse_isScopedToEachClass() {
        val result = MangaBubbleDetectionPostprocessor.processAll(
            imageWidth = 120,
            imageHeight = 100,
            labels = longArrayOf(0L, 0L, 2L),
            boxes = arrayOf(
                floatArrayOf(10f, 20f, 90f, 80f),
                floatArrayOf(11f, 20f, 90f, 80f),
                floatArrayOf(10f, 20f, 90f, 80f),
            ),
            scores = floatArrayOf(0.9f, 0.8f, 0.85f),
        )

        assertEquals(
            listOf(
                MangaBubbleDetectionPostprocessor.Kind.BUBBLE,
                MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
            ),
            result.map { it.kind },
        )
    }

    @Test
    fun process_tableDriven_collapsesOnlyNearDuplicateBubbleBoxes() {
        data class Case(
            val name: String,
            val boxes: Array<FloatArray>,
            val scores: FloatArray,
            val expectedConfidences: List<Float>,
        )
        val cases = listOf(
            Case(
                name = "identical boxes keep highest confidence",
                boxes = arrayOf(
                    floatArrayOf(10f, 10f, 90f, 90f),
                    floatArrayOf(10f, 10f, 90f, 90f),
                ),
                scores = floatArrayOf(0.72f, 0.91f),
                expectedConfidences = listOf(0.91f),
            ),
            Case(
                name = "minor coordinate jitter is a duplicate",
                boxes = arrayOf(
                    floatArrayOf(10f, 10f, 90f, 90f),
                    floatArrayOf(12f, 11f, 91f, 90f),
                ),
                scores = floatArrayOf(0.91f, 0.72f),
                expectedConfidences = listOf(0.91f),
            ),
            Case(
                name = "overlapping neighboring bubbles remain distinct",
                boxes = arrayOf(
                    floatArrayOf(10f, 10f, 90f, 90f),
                    floatArrayOf(50f, 10f, 130f, 90f),
                ),
                scores = floatArrayOf(0.91f, 0.72f),
                expectedConfidences = listOf(0.91f, 0.72f),
            ),
            Case(
                name = "unique low confidence bubble remains",
                boxes = arrayOf(floatArrayOf(10f, 10f, 90f, 90f)),
                scores = floatArrayOf(0.31f),
                expectedConfidences = listOf(0.31f),
            ),
        )

        cases.forEach { case ->
            val result = MangaBubbleDetectionPostprocessor.process(
                imageWidth = 160,
                imageHeight = 120,
                labels = LongArray(case.boxes.size) { 0L },
                boxes = case.boxes,
                scores = case.scores,
            )

            assertEquals(
                case.name,
                case.expectedConfidences,
                result.map(MangaBubbleDetectionPostprocessor.Detection::confidence),
            )
        }
    }
}
