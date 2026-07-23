package com.gameocr.app.service

import com.gameocr.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranslationLogElapsedPolicyTest {

    @Test
    fun defaultSettings_useIndividualElapsedTime() {
        assertFalse(Settings().batchCumulativeCompletionTimeEnabled)
    }

    @Test
    fun resolve_tableDriven_selectsIndividualOrCumulativeElapsedTime() {
        data class Case(
            val name: String,
            val developerMode: Boolean,
            val cumulativeEnabled: Boolean,
            val itemElapsedMs: Long?,
            val batchElapsedMs: Long,
            val expectedMs: Long,
        )

        val cases = listOf(
            Case("default uses item", false, false, 900L, 4_000L, 900L),
            Case("developer mode alone uses item", true, false, 900L, 4_000L, 900L),
            Case("hidden stale toggle uses item", false, true, 900L, 4_000L, 900L),
            Case("developer cumulative toggle uses batch", true, true, 900L, 4_000L, 4_000L),
            Case("missing item falls back to batch", true, false, null, 4_000L, 4_000L),
            Case("zero-cost cache hit stays zero", true, false, 0L, 4_000L, 0L),
            Case("negative item is clamped", true, false, -5L, 4_000L, 0L),
            Case("negative batch is clamped", true, true, 900L, -5L, 0L),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedMs,
                TranslationLogElapsedPolicy.resolve(
                    developerOptionsEnabled = case.developerMode,
                    batchCumulativeCompletionTimeEnabled = case.cumulativeEnabled,
                    itemElapsedMs = case.itemElapsedMs,
                    batchElapsedMs = case.batchElapsedMs,
                ),
            )
        }
    }
}
