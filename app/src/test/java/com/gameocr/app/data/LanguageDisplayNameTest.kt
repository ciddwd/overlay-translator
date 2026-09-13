package com.gameocr.app.data

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LanguageDisplayNameTest {
    @Test fun namesIncludeRegionalVoices_tableDriven() {
        listOf("ko" to "Korean", "ja" to "Japanese", "en-US" to "English (United States)",
            "en_US" to "English (United States)", "fr" to "French", "" to "").forEach { (tag, name) ->
            assertEquals(tag, name, languageDisplayName(tag, Locale.ENGLISH))
        }
        listOf("ko", "ja", "zh-CN", "en-US").forEach { tag ->
            assertNotEquals(tag, languageDisplayName(tag, Locale.SIMPLIFIED_CHINESE))
        }
    }
}
