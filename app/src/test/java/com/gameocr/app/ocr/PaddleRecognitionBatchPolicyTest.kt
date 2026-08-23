package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleRecognitionBatchPolicyTest {

    @Test
    fun plan_tableDriven_groupsSimilarWidthsAndPreservesPositions() {
        data class Case(
            val name: String,
            val widths: List<Int>,
            val batchSize: Int,
            val ratio: Float,
            val expected: List<List<Int>>,
        )

        val cases = listOf(
            Case("empty", emptyList(), 4, 2f, emptyList()),
            Case("four similar crops", listOf(80, 100, 120, 140), 4, 2f, listOf(listOf(0, 1, 2, 3))),
            Case("batch size splits", listOf(80, 90, 100, 110, 120), 4, 2f, listOf(listOf(0, 1, 2, 3), listOf(4))),
            Case("wide outlier gets its own bucket", listOf(80, 90, 500), 4, 2f, listOf(listOf(0, 1), listOf(2))),
            Case("unsorted input returns original positions", listOf(500, 80, 100), 4, 2f, listOf(listOf(1, 2), listOf(0))),
            Case("invalid widths are made safe", listOf(0, -2, 1), 4, 2f, listOf(listOf(0, 1, 2))),
            Case("batch one is serial", listOf(200, 100, 300), 1, 2f, listOf(listOf(1), listOf(0), listOf(2))),
            Case("invalid ratio is strict", listOf(80, 81), 4, Float.NaN, listOf(listOf(0), listOf(1))),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                PaddleRecognitionBatchPolicy.plan(
                    targetWidths = case.widths,
                    maxBatchSize = case.batchSize,
                    maxWidthRatio = case.ratio,
                ),
            )
        }
    }
}
