package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.Settings
import org.junit.Assert.assertNull
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
            Case("Korean v5 supports Korean locale", PaddleModelVersion.V5_KOREAN, "ko-KR", true),
            Case("Korean v5 recommends a Latin OCR for English pages", PaddleModelVersion.V5_KOREAN, "en", false),
            Case("Korean v5 does not bundle Japanese", PaddleModelVersion.V5_KOREAN, "ja", false),
            Case("Korean v5 does not bundle Chinese", PaddleModelVersion.V5_KOREAN, "zh-CN", false),
            Case("East Slavic Russian", PaddleModelVersion.V5_ESLAV, "ru", true),
            Case("East Slavic Russian region", PaddleModelVersion.V5_ESLAV, "ru-RU", true),
            Case("East Slavic locale casing", PaddleModelVersion.V5_ESLAV, "RU_ru", true),
            Case("East Slavic Belarusian", PaddleModelVersion.V5_ESLAV, "be", true),
            Case("East Slavic Ukrainian", PaddleModelVersion.V5_ESLAV, "uk", true),
            Case("East Slavic English", PaddleModelVersion.V5_ESLAV, "en", true),
            Case("East Slavic excludes Bulgarian", PaddleModelVersion.V5_ESLAV, "bg", false),
            Case("East Slavic excludes Korean", PaddleModelVersion.V5_ESLAV, "ko", false),
            Case("East Slavic excludes Japanese", PaddleModelVersion.V5_ESLAV, "ja", false),
            Case("East Slavic excludes Chinese", PaddleModelVersion.V5_ESLAV, "zh", false),
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
    fun eastSlavicRecommendationPrefersLocalEvenWithCloudCredentials_tableDriven() {
        for (code in listOf("ru", "ru-RU", "RU_ru", "be-BY", "uk-UA")) {
            for (engine in listOf(OcrEngineKind.ML_KIT_LATIN, OcrEngineKind.PADDLE_ONNX,
                OcrEngineKind.BAIDU, OcrEngineKind.TENCENT)) {
                for (hasKeys in listOf(false, true)) {
                    val rec = OcrLanguageCapability.recommendFor(
                        code, currentEngine = engine, hasBaiduKey = hasKeys, hasTencentKey = hasKeys,
                    )!!
                    org.junit.Assert.assertEquals("$code $engine $hasKeys", OcrEngineKind.PADDLE_ONNX, rec.engine)
                    org.junit.Assert.assertEquals(PaddleModelVersion.V5_ESLAV, rec.paddleModelVersion)
                    assertFalse(rec.keysMissing)
                }
            }
        }
        org.junit.Assert.assertEquals(OcrEngineKind.ML_KIT_LATIN,
            OcrLanguageCapability.recommendFor("en")!!.engine)
        assertNull(OcrLanguageCapability.preferredPaddleModelVersion("bg"))
        assertNull(OcrLanguageCapability.preferredPaddleModelVersion("auto"))
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
