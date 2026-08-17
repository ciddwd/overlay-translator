package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWordLookupCoordinatorTest {

    @Test
    fun lookup_tableDriven_prefersDictionaryAndFallsBackSafely() = runBlocking {
        data class Case(
            val name: String,
            val wordResult: WordResult?,
            val translation: String? = "普通译文",
            val dictionaryFailure: Boolean = false,
            val translationFailure: Boolean = false,
            val expected: String?,
            val expectedDictionary: Boolean,
            val expectedTranslateCalls: Int,
            val expectedError: Boolean,
        )

        listOf(
            Case(
                name = "definitions are joined for the compact line",
                wordResult = WordResult(definitions = listOf("释义"), fallbackTranslation = "兜底译文"),
                expected = "释义",
                expectedDictionary = true,
                expectedTranslateCalls = 0,
                expectedError = false,
            ),
            Case(
                name = "first definition avoids another request",
                wordResult = WordResult(pos = listOf("v."), definitions = listOf("更新", "升级")),
                expected = "更新、升级",
                expectedDictionary = true,
                expectedTranslateCalls = 0,
                expectedError = false,
            ),
            Case(
                name = "fallback-only dictionary result is retained",
                wordResult = WordResult(fallbackTranslation = "更新"),
                expected = "更新",
                expectedDictionary = true,
                expectedTranslateCalls = 0,
                expectedError = false,
            ),
            Case(
                name = "unsupported dictionary uses normal translation",
                wordResult = null,
                expected = "普通译文",
                expectedDictionary = false,
                expectedTranslateCalls = 1,
                expectedError = false,
            ),
            Case(
                name = "dictionary failure still uses normal translation",
                wordResult = null,
                dictionaryFailure = true,
                expected = "普通译文",
                expectedDictionary = false,
                expectedTranslateCalls = 1,
                expectedError = false,
            ),
            Case(
                name = "both paths fail",
                wordResult = null,
                dictionaryFailure = true,
                translationFailure = true,
                expected = null,
                expectedDictionary = false,
                expectedTranslateCalls = 1,
                expectedError = true,
            ),
        ).forEach { case ->
            val translator = TrackingTranslator(
                wordResult = case.wordResult,
                translation = case.translation,
                dictionaryFailure = case.dictionaryFailure,
                translationFailure = case.translationFailure,
            )
            val outcome = FloatingWordLookupCoordinator(translator).execute(
                word = "update",
                settings = Settings(
                    sourceLang = "ja",
                    translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT,
                    runtimeTranslationContext = "previous frame",
                    runtimeTranslationPromptContext = RuntimeTranslationPromptContext(
                        currentApplication = "sample app",
                        currentPage = listOf("same frame"),
                    ),
                ),
            )

            assertEquals(case.name, case.expected, outcome.translation)
            assertEquals(case.name, case.expectedDictionary, outcome.wordResult != null)
            assertEquals(case.name, 1, translator.dictionaryCalls)
            assertEquals(case.name, case.expectedTranslateCalls, translator.translateCalls)
            assertEquals(case.name, case.expectedError, outcome.error != null)
            assertEquals(case.name, outcome.hasDetails, case.expected != null || case.expectedDictionary)
            translator.receivedSettings.forEach { received ->
                assertEquals(case.name, "en", received.sourceLang)
                assertEquals(case.name, TranslationContextMode.FAST_PER_SEGMENT, received.translationContextMode)
                assertEquals(case.name, "", received.runtimeTranslationContext)
                assertEquals(case.name, RuntimeTranslationPromptContext(), received.runtimeTranslationPromptContext)
                assertNull(case.name, received.runtimeTranslationVisualContext)
            }
        }
    }

    @Test
    fun cancellation_isNeverConvertedIntoLookupFailure() = runBlocking {
        val translator = TrackingTranslator(
            wordResult = null,
            translation = null,
            dictionaryCancellation = true,
        )

        val result = runCatching {
            FloatingWordLookupCoordinator(translator).execute("update", Settings())
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(result.isSuccess)
        assertEquals(0, translator.translateCalls)
    }

    private class TrackingTranslator(
        private val wordResult: WordResult?,
        private val translation: String?,
        private val dictionaryFailure: Boolean = false,
        private val translationFailure: Boolean = false,
        private val dictionaryCancellation: Boolean = false,
    ) : Translator {
        var dictionaryCalls = 0
        var translateCalls = 0
        val receivedSettings = mutableListOf<Settings>()

        override suspend fun translate(source: String, settings: Settings): String? {
            translateCalls += 1
            receivedSettings += settings
            if (translationFailure) error("translation failed")
            return translation
        }

        override fun translateStream(source: String, settings: Settings): Flow<String> = emptyFlow()

        override suspend fun translateWord(source: String, settings: Settings): WordResult? {
            dictionaryCalls += 1
            receivedSettings += settings
            if (dictionaryCancellation) throw CancellationException("cancelled")
            if (dictionaryFailure) error("dictionary failed")
            return wordResult
        }
    }
}
