package com.gameocr.app.tts

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSourceLanguageWiringTest {
    @Test
    fun sourceEntrypoints_tableDriven_shareIdentifierButTranslatedSpeechKeepsTarget() {
        val service = source("service/CaptureService.kt")
        val pipeline = service.substringAfter("private suspend fun runWordSelectPipeline(").substringBefore("private fun cropRect")
        val processText = source("translate/ProcessTextTranslateActivity.kt")
        val router = source("tts/RoutingTtsEngine.kt")
        listOf(
            "source evidence callback" to pipeline.contains("sourceEvidence = { sourceLanguageEvidence }"),
            "explicit OCR fallback tags not trusted as detected" to pipeline.replace(Regex("\\s+"), " ").contains("settings.ocrEngine == OcrEngineKind.ML_KIT_AUTO && settings.sourceLang == \"auto\""),
            "language available before source text" to (pipeline.indexOf("sourceLanguageEvidence = if") in 0 until pipeline.indexOf("card.updateSource(text)")),
            "source copy panel identifies text" to service.substringAfter("private fun showTranslationBlockCopyPanel").substringBefore("private fun wordSelectTtsAction").contains("sourceEvidence = { emptyList() }"),
            "process text source uses shared resolver" to processText.contains("speechEngine.toggleSource(spokenText, spokenSettings, playbackId)"),
            "selected process text uses shared resolver" to processText.contains("speechEngine.speakSource(spokenText, spokenSettings, playbackId)"),
            "translation does not use source detection" to router.contains("speakWithLanguage(text, settings, playbackId, sourceEvidence = null)"),
            "translation still uses target language" to router.contains("resolvedSpokenTtsLanguageTag(normalized, settings.targetLang)"),
            "same identifier as OCR" to source("tts/SourceTtsLanguageResolver.kt").contains("private val identifier: OcrTextLanguageIdentifier"),
            "pending language request has a playback token" to (router.indexOf("playbackCoordinator.begin(") < router.indexOf("sourceLanguageResolver.resolve(")),
            "invalidated language request never reaches backend" to (router.indexOf("if (!preparation.awaitReady(token)) return") < router.indexOf("systemTtsEngine.speak(")),
            "online voices also receive actual language" to router.contains("httpTtsEngine.speak(normalized, routedSettings, token)"),
        ).forEach { (name, ok) -> assertTrue(name, ok) }
    }

    private fun source(path: String): String = listOf(File("src/main/java/com/gameocr/app/$path"),
        File("app/src/main/java/com/gameocr/app/$path")).first(File::isFile).readText()
}
