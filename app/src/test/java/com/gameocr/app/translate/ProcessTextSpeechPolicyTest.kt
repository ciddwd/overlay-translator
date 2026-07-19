package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TtsProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTextSpeechPolicyTest {

    @Test
    fun speechSettings_tableDriven_routesSourceAndTranslationLanguagesForEveryProvider() {
        data class Case(
            val name: String,
            val source: String,
            val target: String,
            val role: ProcessTextSpeechRole,
            val expected: String,
        )

        val cases = listOf(
            Case("Japanese source", "ja", "zh-CN", ProcessTextSpeechRole.SOURCE, "ja"),
            Case("automatic source", "auto", "en", ProcessTextSpeechRole.SOURCE, "auto"),
            Case("Chinese translation", "ja", "zh-CN", ProcessTextSpeechRole.TRANSLATION, "zh-CN"),
            Case("English translation", "zh-CN", "en-US", ProcessTextSpeechRole.TRANSLATION, "en-US"),
        )

        TtsProvider.entries.forEach { provider ->
            cases.forEach { case ->
                val original = Settings(
                    ttsEnabled = true,
                    ttsProvider = provider,
                    sourceLang = case.source,
                    targetLang = case.target,
                )
                val routed = processTextSpeechSettings(original, case.role)

                assertEquals("$provider/${case.name}", case.expected, routed.targetLang)
                assertEquals("$provider/${case.name}", provider, routed.ttsProvider)
                assertTrue("$provider/${case.name}", routed.ttsEnabled)
            }
        }
    }

    @Test
    fun processTextOverlay_wiresManualSpeechWithoutSpeakingStatusOrFailures() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/translate/ProcessTextTranslateActivity.kt"
        ).readText()

        assertTrue(source.contains("@Inject lateinit var ttsEngine: TtsEngine"))
        assertEquals(2, source.countOccurrences("onSpeakSource = sourceSpeech"))
        assertEquals(1, source.countOccurrences("onSpeakTranslation = translationSpeech.takeIf"))
        assertEquals(1, source.countOccurrences("onSpeakDictionary = dictionarySpeech.takeIf"))
        assertTrue(source.contains("presentation.translationSucceeded"))
        assertTrue(source.contains("speechEngine.toggle("))
        assertTrue(source.contains("speechEngine.speak("))
        assertTrue(source.contains("playbackState = speechEngine.playbackState"))
        assertTrue(source.contains("appScope.launch(Dispatchers.Main.immediate)"))
        assertTrue(source.contains("WordSelectTranslationCoordinator(translator).execute("))
        assertTrue(source.contains("wordResult = outcome.wordResult"))
    }

    @Test
    fun translationPresentation_tableDriven_prefersTranslationThenDictionaryFallback() {
        data class Case(
            val name: String,
            val translation: String? = null,
            val wordResult: WordResult? = null,
            val expectedText: String,
            val expectedSucceeded: Boolean,
        )

        val cases = listOf(
            Case("regular translation", translation = "译文", expectedText = "译文", expectedSucceeded = true),
            Case(
                "dictionary fallback",
                wordResult = WordResult(
                    definitions = listOf("释义"),
                    fallbackTranslation = "兜底译文",
                ),
                expectedText = "兜底译文",
                expectedSucceeded = true,
            ),
            Case(
                "dictionary first definition",
                wordResult = WordResult(definitions = listOf("第一释义", "第二释义")),
                expectedText = "第一释义",
                expectedSucceeded = true,
            ),
            Case(
                "translation wins over dictionary",
                translation = "完整译文",
                wordResult = WordResult(definitions = listOf("词典释义")),
                expectedText = "完整译文",
                expectedSucceeded = true,
            ),
            Case("empty outcome", expectedText = "失败", expectedSucceeded = false),
            Case(
                "whitespace dictionary fallback",
                wordResult = WordResult(pos = listOf("名词"), fallbackTranslation = "  "),
                expectedText = "失败",
                expectedSucceeded = false,
            ),
        )

        cases.forEach { case ->
            val actual = processTextTranslationPresentation(
                outcome = WordSelectTranslationOutcome(
                    translation = case.translation,
                    wordResult = case.wordResult,
                    translationError = null,
                    dictionaryError = null,
                    chunkCount = 0,
                    firstChunkLatencyMs = null,
                ),
                failureText = "失败",
            )
            assertEquals(case.name, case.expectedText, actual.translation)
            assertEquals(case.name, case.expectedSucceeded, actual.translationSucceeded)
        }
    }

    private fun String.countOccurrences(fragment: String): Int =
        windowed(fragment.length).count { it == fragment }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
