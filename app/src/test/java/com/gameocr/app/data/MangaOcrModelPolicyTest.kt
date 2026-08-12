package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MangaOcrModelPolicyTest {

    @Test
    fun normalize_tableDriven_forcesV6SmallOnlyForMangaOcr() {
        data class Case(
            val name: String,
            val engine: OcrEngineKind,
            val requested: PaddleModelVersion,
            val expected: PaddleModelVersion,
        )
        val cases = listOf(
            Case("legacy V5", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V5_MOBILE, PaddleModelVersion.V6_SMALL),
            Case("tiny", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_TINY, PaddleModelVersion.V6_SMALL),
            Case("small", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_SMALL, PaddleModelVersion.V6_SMALL),
            Case("medium", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_MEDIUM, PaddleModelVersion.V6_SMALL),
            Case("general Paddle", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V5_MOBILE, PaddleModelVersion.V5_MOBILE),
            Case("ML Kit", OcrEngineKind.ML_KIT_JAPANESE, PaddleModelVersion.V6_TINY, PaddleModelVersion.V6_TINY),
        )

        cases.forEach { case ->
            val normalized = MangaOcrModelPolicy.normalize(
                Settings(
                    ocrEngine = case.engine,
                    paddleModelVersion = case.requested,
                )
            )
            assertEquals(case.name, case.expected, normalized.paddleModelVersion)
            assertEquals(
                "${case.name} is idempotent",
                normalized,
                MangaOcrModelPolicy.normalize(normalized),
            )
        }
    }

    @Test
    fun normalize_migratesNestedMangaPresetAndRecomputesHash() {
        val legacyPreset = TranslationPreset(
            id = "legacy_manga",
            name = "Legacy Manga",
            ocrEngine = OcrEngineKind.MANGA_OCR_JA,
            paddleModelVersion = PaddleModelVersion.V5_MOBILE,
            settingsHash = "legacy-hash",
        )

        val normalized = MangaOcrModelPolicy.normalize(
            Settings(translationPresets = listOf(legacyPreset))
        ).translationPresets.single()

        assertEquals(PaddleModelVersion.V6_SMALL, normalized.paddleModelVersion)
        assertNotEquals(legacyPreset.settingsHash, normalized.settingsHash)
        assertEquals(normalized, MangaOcrModelPolicy.normalize(normalized))
    }
}
