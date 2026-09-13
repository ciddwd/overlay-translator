package com.gameocr.app.service

import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsFieldPolicy
import com.gameocr.app.overlay.shouldShowTranslationCardTranslationSection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSelectExtractionTest {
    @Test
    fun settings_tableDriven_defaultOffAndPortableRoundTripKeepIndependentChoices() {
        assertFalse(Settings().wordSelectExtractOnly)
        assertFalse(Json.decodeFromString<Settings>("{}").wordSelectExtractOnly)
        for (extract in listOf(false, true)) {
            for (cardMode in listOf(false, true)) {
                val settings = Settings(wordSelectExtractOnly = extract, wordSelectCardMode = cardMode)
                val restored = SettingsFieldPolicy.decodePortable(SettingsFieldPolicy.encodePortable(settings)).settings
                assertEquals(extract, restored.wordSelectExtractOnly)
                assertEquals(cardMode, restored.wordSelectCardMode)
                assertEquals(settings.overlayTheme, restored.overlayTheme)
                assertEquals(settings.overlayTextStyle, restored.overlayTextStyle)
            }
        }
    }

    @Test
    fun card_tableDriven_extractionNeverShowsTranslationSectionEvenDuringRecognition() {
        for (loading in listOf(false, true)) {
            for (translation in listOf(null, "", "late translated result")) {
                assertFalse(shouldShowTranslationCardTranslationSection(translation, null, loading, textOnly = true))
                assertEquals(loading || !translation.isNullOrBlank(),
                    shouldShowTranslationCardTranslationSection(translation, null, loading))
            }
        }
    }

    @Test
    fun extractionPipeline_tableDriven_reusesCaptureAndReturnsBeforeTranslationOrDictionaryWork() {
        val service = source("service/CaptureService.kt")
        val pipeline = service.substringAfter("private suspend fun runWordSelectPipeline(")
            .substringBefore("private fun cropRect")
        val boundary = pipeline.indexOf("if (extractTextOnly) return")
        assertTrue(boundary >= 0)
        listOf(
            "captureScreenshotWithTiming(shotter, diagId)",
            "ocrEngine.recognize(cropped, settings.ocrEngine, settings)",
            "sortTextBlocksForReading(ocrBlocks)",
            "card.updateSource(text)",
        ).forEach { marker ->
            assertTrue("shared stage before extraction completes: $marker", pipeline.indexOf(marker) in 0 until boundary)
        }
        listOf(
            "WordHeuristic.dictionaryTermOrNull(",
            "WordSelectTranslationCoordinator(translator).execute(",
            "resolveTranslationOutput(",
            "logRepository.pair(",
        ).forEach { marker -> assertTrue("no translation work in extraction: $marker", pipeline.indexOf(marker) > boundary) }
        listOf(
            "image preparation is bypassed" to pipeline.contains("if (extractTextOnly) settings else prepareVisualTranslationSettings("),
            "card is explicitly extraction only" to pipeline.contains("textOnly = extractTextOnly"),
            "word lookup is off" to pipeline.contains("if (!extractTextOnly && settings.dictionaryTapLookupEnabled)"),
            "correction is off" to pipeline.contains("onCorrectTranslation = if (extractTextOnly) null else"),
            "source speech uses actual language evidence" to pipeline.contains("sourceEvidence = { sourceLanguageEvidence }"),
            "translation speech is not created" to pipeline.contains("val translationSpeech = if (extractTextOnly) null else"),
            "dictionary speech is not created" to pipeline.contains("val dictionarySpeech = if (extractTextOnly) null else"),
            "action type frozen at selection" to service.contains("extractTextOnly = plan.extractTextOnly"),
            "capture lock released on every exit" to pipeline.substringAfterLast("} finally {").contains("captureLock.unlock()"),
            "existing empty result error" to pipeline.contains("R.string.word_card_no_text"),
            "existing OCR failure error" to pipeline.contains("R.string.toast_word_select_ocr_failed_format"),
        ).forEach { (name, ok) -> assertTrue(name, ok) }
    }

    @Test
    fun cardStyleAndActions_tableDriven_reuseUserSettingsWithoutSourceTranslationLabels() {
        val card = source("overlay/TranslationCardOverlay.kt")
        listOf(
            "existing card theme" to card.contains("val theme = settings.overlayTheme"),
            "existing shell including opacity and border" to card.contains("background = shellBackground(theme, settings, density)"),
            "source heading is absent" to card.contains("text = if (textOnly) \"\" else context.getString(R.string.word_card_section_source)"),
            "generic copy" to card.contains("if (textOnly) android.R.string.copy else R.string.word_card_btn_copy_source"),
            "generic speech" to card.contains("if (textOnly) R.string.word_card_speak_selection else R.string.word_card_speak_source"),
            "recognition finishes loading" to card.contains("if (textOnlyMode) translationLoading = false"),
            "late dictionary results ignored" to card.contains("if (textOnlyMode) return"),
            "user text size" to card.contains("setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.overlayTextSizeSp.toFloat())"),
            "user font and text style" to card.contains("applyOverlayTextStyle(settings.overlayTextStyle, settingsTypeface(settings))"),
            "select and copy remains available" to card.contains("setTextIsSelectable(true)"),
            "whole text copies current recognized text" to card.contains("copyToClipboard(currentSource)"),
            "speech toggles existing playback" to card.contains("currentSource.takeIf(String::isNotBlank)?.let(action.onToggle)"),
            "selected text speech stays available" to card.contains("onSpeak = action.onStart"),
        ).forEach { (name, ok) -> assertTrue(name, ok) }
    }

    @Test
    fun settingsWiring_tableDriven_autoSaveSearchAndReload() {
        val screen = source("ui/SettingsScreen.kt")
        val repo = source("data/SettingsRepository.kt")
        val viewModel = source("ui/SettingsViewModel.kt")
        val selector = source("overlay/WordSelectOverlay.kt")
        listOf(
            "writes preferences" to repo.contains("prefs[Keys.WordSelectExtractOnly] = next.wordSelectExtractOnly"),
            "reads preferences with default" to repo.contains("wordSelectExtractOnly = this[Keys.WordSelectExtractOnly] ?: default.wordSelectExtractOnly"),
            "updates only this field" to viewModel.contains("repo.update { it.copy(wordSelectExtractOnly = enabled) }"),
            "saves on switch" to screen.contains("viewModel.saveWordSelectExtractOnly(enabled)"),
            "updates saved baseline" to screen.contains("initialSettings = initialSettings?.copy(wordSelectExtractOnly = enabled)"),
            "snapshot includes immediate value" to screen.contains("wordSelectExtractOnly = wordSelectExtractOnly,"),
            "load and import refresh" to (Regex("wordSelectExtractOnly = s.wordSelectExtractOnly").findAll(screen).count() == 2),
            "prior card choice not overwritten" to screen.contains("wordSelectCardMode || wordSelectExtractOnly"),
            "irrelevant display form disabled" to screen.contains("enabled = !wordSelectExtractOnly"),
            "search entry exists" to screen.contains("R.string.settings_word_select_extract_only, listOf("),
            "existing precise action renamed" to selector.contains("if (extractTextOnly) R.string.word_select_btn_extract else R.string.word_select_btn_translate"),
            "quick selection still skips adjustment" to selector.contains("skipAdjustment = skipAdjustment"),
        ).forEach { (name, ok) -> assertTrue(name, ok) }
    }

    @Test
    fun approvedCopy_tableDriven_hasNoAdditionalDescription() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file("src/main/res/values-zh-rCN/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        val strings = (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        mapOf(
            "settings_word_select_extract_only" to "仅提取文字",
            "settings_word_select_extract_only_help" to "开启后，仅识别选框内的文字，不进行翻译。",
            "word_select_btn_extract" to "提取文字",
        ).forEach { (key, text) -> assertEquals(key, text, strings[key]) }
    }

    private fun source(path: String) = file("src/main/java/com/gameocr/app/$path").readText().replace("\r\n", "\n")
    private fun file(path: String) = listOf(File(path), File("app/$path")).first(File::isFile)
}
