package com.gameocr.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaMultiSequenceMetricsTest {

    @Test
    fun parseNativeMetrics_acceptsOnlyCompleteFinitePayload_tableDriven() {
        data class Case(
            val name: String,
            val values: DoubleArray?,
            val expected: LlamaMultiSequence.Metrics?,
        )

        listOf(
            Case(
                name = "valid native phase metrics",
                values = doubleArrayOf(0.1, 611.0, 1_430.0, 2_041.1, 30.0, 39.0),
                expected = LlamaMultiSequence.Metrics(0.1, 611.0, 1_430.0, 2_041.1, 30, 39),
            ),
            Case("missing payload", null, null),
            Case("wrong field count", doubleArrayOf(1.0, 2.0), null),
            Case("negative duration", doubleArrayOf(-1.0, 2.0, 3.0, 4.0, 5.0, 6.0), null),
            Case("non finite duration", doubleArrayOf(Double.NaN, 2.0, 3.0, 4.0, 5.0, 6.0), null),
            Case("fractional token count", doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.5, 6.0), null),
        ).forEach { case ->
            val actual = LlamaMultiSequence.parseNativeMetrics(case.values)
            if (case.expected == null) {
                assertNull(case.name, actual)
            } else {
                assertEquals(case.name, case.expected, actual)
            }
        }
    }
}
