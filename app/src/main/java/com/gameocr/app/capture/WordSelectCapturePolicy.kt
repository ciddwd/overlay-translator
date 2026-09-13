package com.gameocr.app.capture

import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.sortTextBlocksForMergedPage
import com.gameocr.app.ocr.sortTextBlocksForReading
import com.gameocr.app.translate.PageTranslationGroupingPolicy
import com.gameocr.app.translate.planPageTranslationUnits

internal fun wordSelectOcrSettings(settings: Settings, extractTextOnly: Boolean): Settings =
    if (extractTextOnly) settings.copy(renderMode = RenderMode.FLOATING_WINDOW) else settings

/** Extraction uses the same ordering and grouping as the floating translation window, without translation. */
internal fun extractedCardText(blocks: List<TextBlock>, settings: Settings): String {
    val mergeAll = PageTranslationGroupingPolicy.shouldMergeAll(
        RenderMode.FLOATING_WINDOW, settings.mergeAdjacentBlocks, settings.mergeStrength,
    )
    val ordered = if (mergeAll) sortTextBlocksForMergedPage(blocks) else sortTextBlocksForReading(blocks)
    return planPageTranslationUnits(ordered, RenderMode.FLOATING_WINDOW,
        settings.mergeAdjacentBlocks, settings.mergeStrength)
        .map { it.sourceText.trim() }.filter(String::isNotEmpty).joinToString("\n")
}

internal data class WordSelectCapturePlan(
    val saveLastSelection: Boolean,
    val useTranslationCard: Boolean,
    val captureRegionOverride: CaptureRegion?,
    val extractTextOnly: Boolean = false,
)

/**
 * Keeps a word-selection rectangle scoped to the current action. The shared capture-region
 * setting is never part of this plan; remembering a word selection only updates its dedicated
 * word-select field.
 */
internal fun wordSelectCapturePlan(
    rememberLastSelection: Boolean,
    useTranslationCard: Boolean,
    selectedRegion: CaptureRegion,
    extractTextOnly: Boolean = false,
): WordSelectCapturePlan = WordSelectCapturePlan(
    saveLastSelection = rememberLastSelection,
    useTranslationCard = useTranslationCard || extractTextOnly,
    captureRegionOverride = selectedRegion.takeUnless { useTranslationCard || extractTextOnly },
    extractTextOnly = extractTextOnly,
)

/** Preserve the OCR block order and line breaks for extraction; keep translation input unchanged. */
internal fun wordSelectRecognizedText(orderedTexts: List<String>, extractTextOnly: Boolean): String =
    if (extractTextOnly) {
        orderedTexts.map(String::trim).filter(String::isNotEmpty).joinToString("\n")
    } else {
        orderedTexts.joinToString(" ") { it.trim() }.trim()
    }
