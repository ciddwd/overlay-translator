package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSelectTtsUiAuditTest {

    @Test
    fun wordSelectTts_isManualAndProvidesSourceAndTranslationActions() {
        val source = sourceFile("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()
        val pipeline = source.substring(
            source.indexOf("private suspend fun runWordSelectPipeline"),
            source.indexOf("private fun cropRect", source.indexOf("private suspend fun runWordSelectPipeline")),
        )

        val requiredFragments = listOf(
            "onSpeakSource = sourceSpeech",
            "onSpeakTranslation = translationSpeech",
            "onSpeakDictionary = dictionarySpeech",
            "playbackId = \"word-select:${'$'}diagId:source\"",
            "playbackId = \"word-select:${'$'}diagId:translation\"",
            "playbackId = \"word-select:${'$'}diagId:dictionary\"",
            "settings.copy(targetLang = settings.sourceLang)",
        )
        requiredFragments.forEach { fragment ->
            assertTrue("Missing manual word-select TTS wiring: $fragment", pipeline.contains(fragment))
        }
        assertFalse(
            "Word-select completion must not automatically speak the final translation",
            pipeline.contains("speakWordSelectTranslation(displayedTranslation"),
        )

        val speaker = source.substring(
            source.indexOf("private suspend fun speakWordSelectText"),
            source.indexOf("private fun logBlankLikeFrame", source.indexOf("private suspend fun speakWordSelectText")),
        )
        assertTrue("Persistent buttons toggle playback", speaker.contains("ttsEngine.toggle("))
        assertTrue("Selection toolbar starts new playback", speaker.contains("ttsEngine.speak("))
        assertFalse("Toggle must not discard resumable progress", speaker.contains("ttsEngine.stop()"))

        val actionFactory = source.substring(
            source.indexOf("private fun wordSelectTtsAction"),
            source.indexOf("private suspend fun speakWordSelectText"),
        )
        assertTrue(
            "Word-select TTS clicks must preserve UI order on the main dispatcher",
            actionFactory.contains("mainScope.launch {"),
        )
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
