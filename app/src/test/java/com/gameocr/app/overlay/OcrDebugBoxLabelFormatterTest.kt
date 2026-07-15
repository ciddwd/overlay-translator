package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrDebugBoxLabelFormatterTest {

    @Test
    fun format_coversEverySourceAndTranslationVisibilityCombination() {
        data class Case(
            val name: String,
            val showSource: Boolean,
            val showTranslation: Boolean,
            val translation: String,
            val expected: String,
        )

        listOf(
            Case("box only", false, false, "translated", ""),
            Case("source only", true, false, "translated", "OCR: source"),
            Case("translation only", false, true, "translated", "Translation: translated"),
            Case("source and translation", true, true, "translated", "OCR: source\nTranslation: translated"),
            Case("streaming placeholder", true, true, "…", "OCR: source\nTranslation: …"),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                OcrDebugBoxLabelFormatter.format(
                    source = " source ",
                    translation = case.translation,
                    showSource = case.showSource,
                    showTranslation = case.showTranslation,
                    sourceLabel = "OCR",
                    translationLabel = "Translation",
                ),
            )
        }
    }
}
