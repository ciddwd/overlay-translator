package com.gameocr.app.translate

import com.gameocr.app.data.DictionaryLookupMode
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.dictionary.OfflineWordDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWordLookupCoordinatorTest {

    @Test
    fun onlineLookup_tableDriven_neverFallsBackToPlainTranslation() = runBlocking {
        data class Case(
            val name: String,
            val wordResult: WordResult?,
            val dictionaryFailure: Boolean = false,
            val expected: String?,
            val expectedError: Boolean,
        )
        listOf(
            Case("definitions are joined", WordResult(senses = listOf(WordSense("v.", listOf("更新", "升级")))), expected = "更新、升级", expectedError = false),
            Case("fallback translation is retained", WordResult(fallbackTranslation = "更新"), expected = "更新", expectedError = false),
            Case("empty structured response is a miss", null, expected = null, expectedError = false),
            Case("request failure is exposed", null, dictionaryFailure = true, expected = null, expectedError = true),
        ).forEach { case ->
            val translator = TrackingTranslator(case.wordResult, dictionaryFailure = case.dictionaryFailure)
            val outcome = FloatingWordLookupCoordinator(translator).execute("update", onlineSettings())

            assertEquals(case.name, case.expected, outcome.translation)
            assertEquals(case.name, case.expectedError, outcome.error != null)
            assertEquals(case.name, 1, translator.compactCalls)
            assertEquals(case.name, 0, translator.translateCalls)
            translator.receivedSettings.forEach { assertIsolated(case.name, it) }
        }
    }

    @Test
    fun offlineLookup_tableDriven_neverCallsLlmAndNeverFallsBackOnline() = runBlocking {
        data class Case(
            val name: String,
            val result: WordResult?,
            val failure: Boolean = false,
            val expected: String?,
            val expectedError: Boolean,
        )
        listOf(
            Case("offline hit", WordResult(senses = listOf(WordSense("n.", listOf("显示")))), expected = "显示", expectedError = false),
            Case("offline miss", null, expected = null, expectedError = false),
            Case("offline database failure", null, failure = true, expected = null, expectedError = true),
        ).forEach { case ->
            val offline = TrackingOfflineDictionary(case.result, case.failure)
            val translator = TrackingTranslator(WordResult(definitions = listOf("不应调用")))
            val outcome = FloatingWordLookupCoordinator(
                translator = translator,
                offlineDictionary = offline,
            ).execute(
                word = "displayed",
                settings = Settings(dictionaryLookupMode = DictionaryLookupMode.OFFLINE),
            )

            assertEquals(case.name, case.expected, outcome.translation)
            assertEquals(case.name, case.expectedError, outcome.error != null)
            assertEquals(case.name, 1, offline.calls)
            assertEquals(case.name, "en", offline.languages.single())
            assertEquals(case.name, 0, translator.compactCalls)
            assertEquals(case.name, 0, translator.fullCalls)
            assertEquals(case.name, 0, translator.translateCalls)
        }
    }

    @Test
    fun onlineLookup_tableDriven_rejectsNonCloudEnginesWithoutFallback() = runBlocking {
        listOf(
            TranslatorEngine.DEEPL,
            TranslatorEngine.GOOGLE_ML_KIT,
            TranslatorEngine.LOCAL_SAKURA,
        ).forEach { engine ->
            val translator = TrackingTranslator(WordResult(definitions = listOf("不应调用")))
            val outcome = FloatingWordLookupCoordinator(translator).execute(
                "update",
                Settings(dictionaryLookupMode = DictionaryLookupMode.ONLINE, translatorEngine = engine),
            )
            assertTrue(engine.name, outcome.error is OnlineDictionaryUnavailableException)
            assertFalse(engine.name, outcome.hasDetails)
            assertEquals(engine.name, 0, translator.compactCalls)
        }
    }

    @Test
    fun fullLookup_usesFullDictionaryRequestOnly() = runBlocking {
        val translator = TrackingTranslator(
            compactResult = WordResult(definitions = listOf("简要")),
            fullResult = WordResult(lemma = "display", senses = listOf(WordSense("v.", listOf("展示", "陈列")))),
        )
        val outcome = FloatingWordLookupCoordinator(translator).executeFull("displayed", onlineSettings())

        assertEquals("display", outcome.wordResult?.lemma)
        assertEquals(0, translator.compactCalls)
        assertEquals(1, translator.fullCalls)
        assertEquals(0, translator.translateCalls)
    }

    @Test
    fun cancellation_isNeverConvertedIntoLookupFailure() = runBlocking {
        val translator = TrackingTranslator(null, dictionaryCancellation = true)
        val result = runCatching {
            FloatingWordLookupCoordinator(translator).execute("update", onlineSettings())
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(result.isSuccess)
        assertEquals(0, translator.translateCalls)
    }

    @Test
    fun offlineLookup_compactAndFull_preserveDatabaseFailureAndCancellation() = runBlocking {
        for (full in listOf(false, true)) {
            for (failure in listOf(IllegalStateException("invalid dictionary schema"), CancellationException("cancelled"))) {
                val offline = object : OfflineWordDictionary {
                    override suspend fun lookup(word: String, languageCode: String): WordResult? = throw failure
                }
                val translator = TrackingTranslator(null)
                val coordinator = FloatingWordLookupCoordinator(translator, offlineDictionary = offline)
                val result = runCatching {
                    val settings = Settings(dictionaryLookupMode = DictionaryLookupMode.OFFLINE)
                    if (full) coordinator.executeFull("displayed", settings) else coordinator.execute("displayed", settings)
                }
                if (failure is CancellationException) {
                    assertTrue(result.exceptionOrNull() is CancellationException)
                    assertEquals(failure.message, result.exceptionOrNull()?.message)
                } else {
                    assertEquals(failure.javaClass, result.getOrThrow().error?.javaClass)
                    assertEquals(failure.message, result.getOrThrow().error?.message)
                    assertFalse(result.getOrThrow().hasDetails)
                }
                assertEquals(0, translator.compactCalls + translator.fullCalls + translator.translateCalls)
            }
        }
    }

    @Test
    fun onlineCompactCache_tableDriven_reusesOnlyEquivalentRequests() = runBlocking {
        data class Step(val word: String, val settings: Settings, val expectedCalls: Int)
        val translator = TrackingTranslator(WordResult(senses = listOf(WordSense("v.", listOf("展示")))))
        val coordinator = FloatingWordLookupCoordinator(translator)

        listOf(
            Step("displayed", onlineSettings(targetLang = "zh-CN", model = "model-a"), 1),
            Step("DISPLAYED", onlineSettings(targetLang = "zh-CN", model = "model-a"), 1),
            Step("supporters", onlineSettings(targetLang = "zh-CN", model = "model-a"), 2),
            Step("displayed", onlineSettings(targetLang = "zh-TW", model = "model-a"), 3),
            Step("displayed", onlineSettings(targetLang = "zh-CN", model = "model-b"), 4),
        ).forEach { step ->
            coordinator.execute(step.word, step.settings)
            assertEquals(step.word, step.expectedCalls, translator.compactCalls)
        }
    }

    @Test
    fun concurrentSameWord_reusesTheInFlightOnlineRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val translator = object : Translator {
            override suspend fun translate(source: String, settings: Settings): String? = null
            override fun translateStream(source: String, settings: Settings): Flow<String> = emptyFlow()
            override suspend fun translateWordCompact(source: String, settings: Settings): WordResult {
                calls += 1
                started.complete(Unit)
                release.await()
                return WordResult(senses = listOf(WordSense("n.", listOf("支持者"))))
            }
        }
        val coordinator = FloatingWordLookupCoordinator(translator)

        val first = async { coordinator.execute("supporters", onlineSettings()) }
        started.await()
        val second = async { coordinator.execute("SUPPORTERS", onlineSettings()) }
        release.complete(Unit)

        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    private fun onlineSettings(targetLang: String = "zh-CN", model: String = "model-a"): Settings = Settings(
        model = model,
        targetLang = targetLang,
        dictionaryLookupMode = DictionaryLookupMode.ONLINE,
        translatorEngine = TranslatorEngine.OPENAI,
        sourceLang = "ja",
        translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT,
        runtimeTranslationContext = "previous frame",
        runtimeTranslationPromptContext = RuntimeTranslationPromptContext(
            currentApplication = "sample app",
            currentPage = listOf("same frame"),
        ),
    )

    private fun assertIsolated(name: String, received: Settings) {
        assertEquals(name, "en", received.sourceLang)
        assertEquals(name, TranslationContextMode.FAST_PER_SEGMENT, received.translationContextMode)
        assertEquals(name, "", received.runtimeTranslationContext)
        assertEquals(name, RuntimeTranslationPromptContext(), received.runtimeTranslationPromptContext)
        assertNull(name, received.runtimeTranslationVisualContext)
    }

    private class TrackingOfflineDictionary(
        private val result: WordResult?,
        private val failure: Boolean,
    ) : OfflineWordDictionary {
        var calls = 0
        val languages = mutableListOf<String>()

        override suspend fun lookup(word: String, languageCode: String): WordResult? {
            calls += 1
            languages += languageCode
            if (failure) error("offline failure")
            return result
        }
    }

    private class TrackingTranslator(
        private val compactResult: WordResult?,
        private val fullResult: WordResult? = compactResult,
        private val dictionaryFailure: Boolean = false,
        private val dictionaryCancellation: Boolean = false,
    ) : Translator {
        var compactCalls = 0
        var fullCalls = 0
        var translateCalls = 0
        val receivedSettings = mutableListOf<Settings>()

        override suspend fun translate(source: String, settings: Settings): String? {
            translateCalls += 1
            receivedSettings += settings
            return "普通译文"
        }

        override fun translateStream(source: String, settings: Settings): Flow<String> = emptyFlow()

        override suspend fun translateWordCompact(source: String, settings: Settings): WordResult? {
            compactCalls += 1
            receivedSettings += settings
            if (dictionaryCancellation) throw CancellationException("cancelled")
            if (dictionaryFailure) error("dictionary failed")
            return compactResult
        }

        override suspend fun translateWord(source: String, settings: Settings): WordResult? {
            fullCalls += 1
            receivedSettings += settings
            if (dictionaryFailure) error("dictionary failed")
            return fullResult
        }
    }
}
