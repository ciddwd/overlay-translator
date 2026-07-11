package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguageCapabilityTest {

    @Test
    fun paddleAiStudioPpOcrV6_supportsChineseJapaneseAndLatinButNotKorean() {
        data class Case(
            val languageCode: String,
            val expected: Boolean
        )

        val cases = listOf(
            Case("auto", true),
            Case("zh-CN", true),
            Case("zh-TW", true),
            Case("ja", true),
            Case("en", true),
            Case("fr", true),
            Case("de", true),
            Case("ko", false),
            Case("ar", false)
        )

        val settings = Settings(ocrEngine = OcrEngineKind.PADDLE_AI_STUDIO)
        cases.forEach { case ->
            val supported = OcrLanguageCapability.supports(settings, case.languageCode)
            if (case.expected) {
                assertTrue(case.languageCode, supported)
            } else {
                assertFalse(case.languageCode, supported)
            }
        }
    }
}
