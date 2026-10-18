package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoOcrLanguageTileTest {
    @Test fun languageGlyphsMatchTheReferenceAndHandleAliases_tableDriven() {
        listOf("zh-CN" to "中", "zh_TW" to "中", " JA-jp " to "あ", "ko" to "한",
            "en-US" to "A", "fr" to "É", "iw" to "א", "hi" to "अ", "be" to "Я",
            "ar" to "ع", "th" to "ก", "de" to "DE", "nb" to "NO").forEach { (code, glyph) ->
            assertEquals(code, glyph, autoOcrLanguageGlyph(code))
        }
    }
}
