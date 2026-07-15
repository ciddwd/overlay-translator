package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WordSelectTranslationCoordinatorTest {
    @Test
    fun executionCases_tableDriven() = runBlocking {
        data class Case(
            val name: String,
            val streaming: Boolean,
            val chunks: List<String> = emptyList(),
            val single: String? = null,
            val failTranslation: Boolean = false,
            val expectedUpdates: List<String>,
            val expectTranslationError: Boolean = false,
        )

        val cases = listOf(
            Case(
                name = "streaming emits cumulative updates",
                streaming = true,
                chunks = listOf("par", "partial"),
                expectedUpdates = listOf("par", "partial"),
            ),
            Case(
                name = "non streaming emits once",
                streaming = false,
                single = "complete",
                expectedUpdates = listOf("complete"),
            ),
            Case(
                name = "translation failure is reported without a dictionary result",
                streaming = true,
                failTranslation = true,
                expectedUpdates = emptyList(),
                expectTranslationError = true,
            ),
        )

        cases.forEach { case ->
            val updates = mutableListOf<String>()
            val translator = FakeTranslator(
                chunks = case.chunks,
                single = case.single,
                failTranslation = case.failTranslation,
            )
            val outcome = WordSelectTranslationCoordinator(translator, nowMs = { 100L }).execute(
                source = "source",
                settings = Settings(streamingTranslate = case.streaming),
                dictionaryTerm = null,
                onPartialTranslation = updates::add,
                onWordResult = {},
            )

            assertEquals(case.name, case.expectedUpdates, updates)
            assertEquals(case.name, case.expectedUpdates.lastOrNull(), outcome.translation)
            assertNull(case.name, outcome.wordResult)
            assertEquals(case.name, case.expectedUpdates.size, outcome.chunkCount)
            if (case.expectTranslationError) assertNotNull(case.name, outcome.translationError)
            else assertNull(case.name, outcome.translationError)
        }
    }

    @Test
    fun dictionaryFirst_tableDriven_onlyFallsBackWhenNoDictionaryExists() = runBlocking {
        data class Case(
            val name: String,
            val dictionaryTerm: String?,
            val dictionaryResult: WordResult?,
            val failDictionary: Boolean = false,
            val streaming: Boolean = true,
            val expectedUpdates: List<String>,
            val expectedWordResult: WordResult? = null,
            val expectedDictionaryCalls: Int,
            val expectedStreamCalls: Int,
            val expectedSingleCalls: Int,
            val expectedDictionaryError: Boolean = false,
            val expectedStages: List<WordSelectTranslationStage>,
        )

        val validDictionary = WordResult(
            phonetic = "/term/",
            definitions = listOf("definition"),
        )
        val cases = listOf(
            Case(
                name = "valid dictionary skips primary translation",
                dictionaryTerm = "term",
                dictionaryResult = validDictionary,
                expectedUpdates = emptyList(),
                expectedWordResult = validDictionary,
                expectedDictionaryCalls = 1,
                expectedStreamCalls = 0,
                expectedSingleCalls = 0,
                expectedStages = listOf(
                    WordSelectTranslationStage.DICTIONARY_STARTED,
                    WordSelectTranslationStage.DICTIONARY_FINISHED,
                ),
            ),
            Case(
                name = "empty dictionary falls back to streaming",
                dictionaryTerm = "term",
                dictionaryResult = WordResult(),
                expectedUpdates = listOf("streamed"),
                expectedDictionaryCalls = 1,
                expectedStreamCalls = 1,
                expectedSingleCalls = 0,
                expectedStages = dictionaryThenPrimaryStages(),
            ),
            Case(
                name = "null dictionary falls back to streaming",
                dictionaryTerm = "term",
                dictionaryResult = null,
                expectedUpdates = listOf("streamed"),
                expectedDictionaryCalls = 1,
                expectedStreamCalls = 1,
                expectedSingleCalls = 0,
                expectedStages = dictionaryThenPrimaryStages(),
            ),
            Case(
                name = "dictionary failure falls back to streaming",
                dictionaryTerm = "term",
                dictionaryResult = null,
                failDictionary = true,
                expectedUpdates = listOf("streamed"),
                expectedDictionaryCalls = 1,
                expectedStreamCalls = 1,
                expectedSingleCalls = 0,
                expectedDictionaryError = true,
                expectedStages = dictionaryThenPrimaryStages(),
            ),
            Case(
                name = "ineligible text starts streaming directly",
                dictionaryTerm = null,
                dictionaryResult = validDictionary,
                expectedUpdates = listOf("streamed"),
                expectedDictionaryCalls = 0,
                expectedStreamCalls = 1,
                expectedSingleCalls = 0,
                expectedStages = primaryStages(),
            ),
            Case(
                name = "null dictionary respects non-streaming setting",
                dictionaryTerm = "term",
                dictionaryResult = null,
                streaming = false,
                expectedUpdates = listOf("single"),
                expectedDictionaryCalls = 1,
                expectedStreamCalls = 0,
                expectedSingleCalls = 1,
                expectedStages = dictionaryThenPrimaryStages(),
            ),
        )

        cases.forEach { case ->
            val updates = mutableListOf<String>()
            val words = mutableListOf<WordResult>()
            val stages = mutableListOf<WordSelectTranslationStage>()
            val translator = TrackingTranslator(
                dictionaryResult = case.dictionaryResult,
                failDictionary = case.failDictionary,
            )
            val outcome = WordSelectTranslationCoordinator(translator).execute(
                source = "source",
                settings = Settings(streamingTranslate = case.streaming),
                dictionaryTerm = case.dictionaryTerm,
                onPartialTranslation = updates::add,
                onWordResult = words::add,
                onStage = stages::add,
            )

            assertEquals(case.name, case.expectedUpdates, updates)
            assertEquals(case.name, case.expectedWordResult, outcome.wordResult)
            assertEquals(case.name, listOfNotNull(case.expectedWordResult), words)
            assertEquals(case.name, case.expectedDictionaryCalls, translator.dictionaryCalls)
            assertEquals(case.name, case.expectedStreamCalls, translator.streamCalls)
            assertEquals(case.name, case.expectedSingleCalls, translator.singleCalls)
            assertEquals(case.name, case.expectedDictionaryError, outcome.dictionaryError != null)
            assertEquals(case.name, case.expectedStages, stages)
        }
    }

    private class TrackingTranslator(
        private val dictionaryResult: WordResult?,
        private val failDictionary: Boolean,
    ) : Translator {
        var dictionaryCalls: Int = 0
        var streamCalls: Int = 0
        var singleCalls: Int = 0

        override suspend fun translate(source: String, settings: Settings): String {
            singleCalls += 1
            return "single"
        }

        override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
            streamCalls += 1
            emit("streamed")
        }

        override suspend fun translateWord(source: String, settings: Settings): WordResult? {
            dictionaryCalls += 1
            if (failDictionary) error("dictionary failed")
            return dictionaryResult
        }
    }

    private class FakeTranslator(
        private val chunks: List<String>,
        private val single: String?,
        private val failTranslation: Boolean,
    ) : Translator {
        override suspend fun translate(source: String, settings: Settings): String? {
            if (failTranslation) error("translation failed")
            return single
        }

        override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
            if (failTranslation) error("translation failed")
            chunks.forEach { emit(it) }
        }
    }

    companion object {
        private fun primaryStages(): List<WordSelectTranslationStage> = listOf(
            WordSelectTranslationStage.PRIMARY_STARTED,
            WordSelectTranslationStage.FIRST_PARTIAL_VISIBLE,
            WordSelectTranslationStage.PRIMARY_FINISHED,
        )

        private fun dictionaryThenPrimaryStages(): List<WordSelectTranslationStage> = listOf(
            WordSelectTranslationStage.DICTIONARY_STARTED,
            WordSelectTranslationStage.DICTIONARY_FINISHED,
            WordSelectTranslationStage.PRIMARY_STARTED,
            WordSelectTranslationStage.FIRST_PARTIAL_VISIBLE,
            WordSelectTranslationStage.PRIMARY_FINISHED,
        )
    }
}
