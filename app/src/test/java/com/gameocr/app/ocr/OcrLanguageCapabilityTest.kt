package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguageCapabilityTest {

    @Test
    fun paddleOnnxModelVersion_tableDriven_matchesOfficialLanguageCoverage() {
        data class Case(
            val name: String,
            val version: PaddleModelVersion,
            val languageCode: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("v5 supports Japanese", PaddleModelVersion.V5_MOBILE, "ja", true),
            Case("v5 does not bundle French", PaddleModelVersion.V5_MOBILE, "fr", false),
            Case("v6 tiny omits Japanese", PaddleModelVersion.V6_TINY, "ja", false),
            Case("v6 tiny supports French", PaddleModelVersion.V6_TINY, "fr", true),
            Case("v6 tiny supports Traditional Chinese", PaddleModelVersion.V6_TINY, "zh-TW", true),
            Case("v6 small supports Japanese", PaddleModelVersion.V6_SMALL, "ja", true),
            Case("v6 medium supports Quechua", PaddleModelVersion.V6_MEDIUM, "qu", true),
            Case("v6 models do not support Korean", PaddleModelVersion.V6_MEDIUM, "ko", false),
        )

        cases.forEach { case ->
            val supported = OcrLanguageCapability.supports(
                Settings(
                    ocrEngine = OcrEngineKind.PADDLE_ONNX,
                    paddleModelVersion = case.version,
                ),
                case.languageCode,
            )
            if (case.expected) {
                assertTrue(case.name, supported)
            } else {
                assertFalse(case.name, supported)
            }
        }
    }

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
