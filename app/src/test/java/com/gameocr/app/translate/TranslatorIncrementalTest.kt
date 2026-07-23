package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorIncrementalTest {

    @Test
    fun defaultIncrementalBatch_tableDriven_emitsEveryFinalIndexInOrder() = runBlocking {
        data class Case(
            val name: String,
            val sources: List<String>,
            val results: List<String?>,
        )

        val cases = listOf(
            Case("empty batch", emptyList(), emptyList()),
            Case("single success", listOf("a"), listOf("甲")),
            Case("null result keeps its index", listOf("a", "b"), listOf("甲", null)),
            Case("duplicate sources remain distinct", listOf("a", "a", "b"), listOf("甲", "甲", "乙")),
        )

        cases.forEach { case ->
            val updates = mutableListOf<BatchTranslationUpdate>()
            val translator = BatchOnlyTranslator(case.results)
            val returned = translator.translateBatchIncremental(case.sources, Settings(), updates::add)

            assertEquals("${case.name} returned", case.results, returned)
            assertEquals("${case.name} update indexes", case.results.indices.toList(), updates.map { it.index })
            assertEquals("${case.name} update texts", case.results, updates.map { it.text })
            assertTrue(
                "${case.name} elapsed times",
                updates.all { (it.elapsedMs ?: -1L) >= 0L },
            )
        }
    }

    @Test
    fun progressState_tableDriven_rejectsDuplicatesAndReportsPendingIndexes() {
        data class Case(
            val name: String,
            val size: Int,
            val attemptedIndexes: List<Int>,
            val expectedAccepted: List<Boolean>,
            val expectedPending: List<Int>,
        )

        val cases = listOf(
            Case("empty state", 0, listOf(0, -1), listOf(false, false), emptyList()),
            Case("out of order completes all", 3, listOf(2, 0, 1), listOf(true, true, true), emptyList()),
            Case("duplicate is ignored", 3, listOf(1, 1), listOf(true, false), listOf(0, 2)),
            Case("invalid indexes do not mutate", 2, listOf(-1, 2, 0), listOf(false, false, true), listOf(1)),
            Case("negative size becomes empty", -2, listOf(0), listOf(false), emptyList()),
        )

        cases.forEach { case ->
            val state = BatchTranslationProgressState(case.size)
            val accepted = case.attemptedIndexes.map(state::accept)

            assertEquals("${case.name} accepted", case.expectedAccepted, accepted)
            assertEquals("${case.name} pending", case.expectedPending, state.pendingIndexes())
        }
    }

    private class BatchOnlyTranslator(
        private val results: List<String?>,
    ) : Translator {
        override suspend fun translate(source: String, settings: Settings): String? =
            error("not used")

        override fun translateStream(source: String, settings: Settings): Flow<String> = emptyFlow()

        override suspend fun translateBatch(
            sources: List<String>,
            settings: Settings,
        ): List<String?> = results
    }
}
