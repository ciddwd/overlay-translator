package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class YoudaoPicTransLanguageTest {
    @Test fun supportedLanguages_tableDriven_keepExplicitLanguage() {
        listOf(
            "vi" to "vi", "VI" to "vi", "vi-VN" to "vi",
            "th" to "th", "TH" to "th", "th-TH" to "th",
            "en" to "en", "en-US" to "en", "ja" to "ja", "ko" to "ko",
            "fr" to "fr", "de" to "de", "es" to "es", "ru" to "ru",
            "pt-BR" to "pt", "it" to "it", "zh" to "zh-CHS",
            "zh-CN" to "zh-CHS", "zh-TW" to "zh-CHT", "zh-Hant" to "zh-CHT",
        ).forEach { (input, expected) -> assertEquals(input, expected, YoudaoPicTransTranslator.mapLang(input)) }
    }

    @Test fun existingFallback_tableDriven_unchanged() {
        // Unsupported targets remain a known limitation, not newly supported languages.
        listOf("auto", "AUTO", "", "unknown", "kn", "kn-IN", " vi ").forEach {
            assertEquals(it, "auto", YoudaoPicTransTranslator.mapLang(it))
        }
    }
}
