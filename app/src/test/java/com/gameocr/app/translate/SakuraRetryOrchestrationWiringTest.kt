package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraRetryOrchestrationWiringTest {

    @Test
    fun contextRetry_usesBoundedSalvageThenIndependentFinalBatch() {
        val source = source("src/main/java/com/gameocr/app/translate/SakuraGalTranslator.kt")

        listOf(
            "SakuraRetryStage.INITIAL",
            "SakuraRetryStage.SALVAGE",
            "SakuraRetryPlanPolicy.structuralFailure",
            "SakuraRetryPlanPolicy.rejectedLines",
            "recoverIsolatedFailures(",
            "super.translateBatchIncremental(",
            "settings.copy(retryFailedTranslation = false)",
            "pendingIndexes.getOrNull(update.index)",
            "line.retryable && !settings.retryFailedTranslation",
            "context lines preserved",
        ).forEach { marker ->
            assertTrue(marker, source.contains(marker))
        }
        assertFalse("legacy retry boolean must not return early", source.contains("|| retry) return"))
        assertFalse("retry recovery must not recurse without a bound", source.contains("depth = depth + 1"))
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
