package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {

    @Test
    fun dbnetUnclipRatio_defaultsKeepPaddleAndMangaOcrSeparate() {
        val settings = Settings()

        assertEquals(1.55f, settings.dbnetUnclipRatio, 0.0001f)
        assertEquals(1.65f, settings.mangaOcrDbnetUnclipRatio, 0.0001f)
    }

    @Test
    fun overlayFont_defaultsToSystemFont() {
        val settings = Settings()

        assertEquals("", settings.overlayFontFileName)
        assertEquals("", settings.overlayFontDisplayName)
        assertEquals(emptyList<OverlayFontEntry>(), settings.overlayFonts)
    }

    @Test
    fun textOrientationAutoDetect_defaultsToEnabled() {
        val settings = Settings()

        assertEquals(true, settings.textOrientationAutoDetect)
    }

    @Test
    fun dbnetUnclipRatioFor_routesOnlyMangaOcrToMangaSpecificMargin() {
        data class Case(
            val engine: OcrEngineKind,
            val expected: Float,
        )

        val settings = Settings(
            dbnetUnclipRatio = 1.31f,
            mangaOcrDbnetUnclipRatio = 1.79f,
        )
        val cases = listOf(
            Case(OcrEngineKind.PADDLE_ONNX, 1.31f),
            Case(OcrEngineKind.MANGA_OCR_JA, 1.79f),
            Case(OcrEngineKind.ML_KIT_AUTO, 1.31f),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, settings.dbnetUnclipRatioFor(case.engine), 0.0001f)
        }
    }
}
