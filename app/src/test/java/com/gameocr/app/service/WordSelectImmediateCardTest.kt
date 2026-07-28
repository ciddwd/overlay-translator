package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSelectImmediateCardTest {

    @Test
    fun wordSelectCard_tableDriven_appearsAfterCleanScreenshotAndBeforeOcr() {
        data class Case(
            val name: String,
            val before: String,
            val after: String,
        )

        val pipeline = wordSelectPipeline()
        val cases = listOf(
            Case(
                name = "card cannot contaminate the screenshot",
                before = "val full = shotter.capture()",
                after = "sourceText = \"\"",
            ),
            Case(
                name = "loading card is visible before OCR starts",
                before = "sourceText = \"\"",
                after = "ocrEngine.recognize(cropped, settings.ocrEngine)",
            ),
            Case(
                name = "recognized source replaces the loading placeholder",
                before = "ocrEngine.recognize(cropped, settings.ocrEngine)",
                after = "card.updateSource(text)",
            ),
            Case(
                name = "recognized source is visible before translation starts",
                before = "card.updateSource(text)",
                after = "WordSelectTranslationCoordinator(translator).execute(",
            ),
        )

        cases.forEach { case ->
            val beforeIndex = pipeline.indexOf(case.before)
            val afterIndex = pipeline.indexOf(case.after)
            assertTrue("${case.name}: missing ${case.before}", beforeIndex >= 0)
            assertTrue("${case.name}: missing ${case.after}", afterIndex >= 0)
            assertTrue(case.name, beforeIndex < afterIndex)
        }

        val screenshotIndex = pipeline.indexOf("val full = shotter.capture()")
        val loadingCardIndex = pipeline.indexOf("sourceText = \"\"")
        val chromeRestoreIndex = pipeline.lastIndexOf(
            "restoreCaptureChromeOnce(showLoading = false)",
            loadingCardIndex,
        )
        assertTrue(
            "capture chrome should be restored only after the clean screenshot and before showing the card",
            chromeRestoreIndex in (screenshotIndex + 1) until loadingCardIndex,
        )
    }

    @Test
    fun wordSelectCard_tableDriven_dismissesLoadingStateOnEarlyFailures() {
        data class Case(
            val name: String,
            val start: String,
            val end: String,
            val searchAfter: String? = null,
        )

        val pipeline = wordSelectPipeline()
        val cases = listOf(
            Case(
                name = "invalid crop",
                start = "if (cropped == null)",
                end = "logWordSelectPerf(\"crop_ready\"",
            ),
            Case(
                name = "OCR exception",
                start = "Timber.w(t, \"Word-select OCR failed\")",
                end = "\"ocr_finished\"",
            ),
            Case(
                name = "empty OCR result",
                start = "if (text.isEmpty())",
                end = "logRepository.info(",
            ),
            Case(
                name = "pipeline cancellation",
                start = "} catch (ce: kotlinx.coroutines.CancellationException) {",
                end = "} catch (t: Throwable) {",
                searchAfter = "\"result_complete\"",
            ),
            Case(
                name = "unexpected pipeline exception",
                start = "} catch (t: Throwable) {",
                end = "} finally {",
                searchAfter = "\"result_complete\"",
            ),
        )

        cases.forEach { case ->
            val searchFrom = case.searchAfter
                ?.let { marker -> pipeline.indexOf(marker).coerceAtLeast(0) }
                ?: 0
            val startIndex = pipeline.indexOf(case.start, searchFrom)
            val endIndex = pipeline.indexOf(case.end, startIndex + 1)
            assertTrue("${case.name}: missing start marker", startIndex >= 0)
            assertTrue("${case.name}: missing end marker", endIndex > startIndex)
            assertTrue(
                "${case.name}: loading card should be dismissed",
                "dismissActiveCard()" in pipeline.substring(startIndex, endIndex),
            )
        }
    }

    @Test
    fun translationCard_tableDriven_transitionsFromRecognizingToSourceText() {
        data class Case(
            val name: String,
            val requiredSource: String,
        )

        val overlay = sourceFile(
            "src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
        ).readText()
        val cases = listOf(
            Case(
                name = "blank source displays a recognition placeholder",
                requiredSource = "context.getString(R.string.word_card_recognizing)",
            ),
            Case(
                name = "source text can be updated without rebuilding the card",
                requiredSource = "fun updateSource(sourceText: String)",
            ),
            Case(
                name = "source view receives OCR text",
                requiredSource = "sourceView?.apply {\n            text = sourceText",
            ),
            Case(
                name = "copy action stays hidden until OCR text exists",
                requiredSource = "copySourceButton?.visibility = if (sourceText.isBlank()) View.GONE else View.VISIBLE",
            ),
            Case(
                name = "speech action follows OCR text availability",
                requiredSource = "speakSourceButton?.visibility = if (",
            ),
        )

        cases.forEach { case ->
            assertTrue(case.name, overlay.contains(case.requiredSource))
        }

        val localizedLoadingText = listOf(
            "src/main/res/values/strings.xml" to "Recognizing selected text…",
            "src/main/res/values-zh-rCN/strings.xml" to "正在识别选中文字…",
        )
        localizedLoadingText.forEach { (path, expected) ->
            assertTrue(
                "$path should define the loading message",
                sourceFile(path).readText().contains(expected),
            )
        }
    }

    private fun wordSelectPipeline(): String {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/service/CaptureService.kt",
        ).readText()
        val start = source.indexOf("private suspend fun runWordSelectPipeline")
        val end = source.indexOf("private fun cropRect", start)
        require(start >= 0 && end > start) { "Word-select pipeline not found" }
        return source.substring(start, end)
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
