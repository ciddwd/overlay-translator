package com.gameocr.app.capture

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
