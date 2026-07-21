package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchGateTest {

    @Test
    fun accepts_tableDrivenAllowsOnlyTheActiveCaptureBatch() {
        data class Case(
            val name: String,
            val batchId: Long?,
            val expected: Boolean,
        )

        val gate = TranslationBatchGate()
        listOf(
            Case("no active batch rejects missing id", null, false),
            Case("no active batch rejects an id", 1L, false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, gate.accepts(case.batchId))
        }

        assertEquals(null, gate.activate(10L))
        listOf(
            Case("active batch accepts itself", 10L, true),
            Case("active batch rejects older id", 9L, false),
            Case("active batch rejects missing id", null, false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, gate.accepts(case.batchId))
        }

        assertEquals(10L, gate.activate(11L))
        listOf(
            Case("replacement rejects previous batch", 10L, false),
            Case("replacement accepts new batch", 11L, true),
        ).forEach { case ->
            assertEquals(case.name, case.expected, gate.accepts(case.batchId))
        }

        assertEquals(11L, gate.invalidate())
        assertFalse("invalidated batch is stale", gate.accepts(11L))
        assertEquals("repeated invalidation has no previous batch", null, gate.invalidate())
    }

    @Test
    fun captureService_tableDrivenWiresCancellationAndGuardsEveryOverlayPath() {
        data class Case(val name: String, val marker: String)

        val source = File("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()
        val cases = listOf(
            Case("clean frame invalidates old work", "cancelActiveTranslationBatch(\"prepareCleanCaptureFrame\")"),
            Case("capture activates its id", "beginTranslationBatch(diagId)"),
            Case("cleanup cancels translation job", "cancelActiveTranslationBatch(\"cleanupCapture\")"),
            Case("block jobs are tracked", "launchTranslationBatch(diagId)"),
            Case("block writes carry capture id", "translationBatchId = diagId"),
            Case("floating writes check capture id", "translationBatchGate.accepts(diagId)"),
            Case("stream events recheck capture id", "ensureCurrentTranslationBatch(diagId)"),
        )

        cases.forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }
    }
}
