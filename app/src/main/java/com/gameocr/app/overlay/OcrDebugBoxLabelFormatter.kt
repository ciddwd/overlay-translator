package com.gameocr.app.overlay

internal object OcrDebugBoxLabelFormatter {
    fun format(
        source: String,
        translation: String,
        showSource: Boolean,
        showTranslation: Boolean,
        sourceLabel: String,
        translationLabel: String,
    ): String = buildList {
        if (showSource) add("$sourceLabel: ${source.trim()}")
        if (showTranslation) add("$translationLabel: ${translation.trim()}")
    }.joinToString("\n")
}
