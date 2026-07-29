package com.gameocr.app.gallery

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.OverlayTextAlignment
import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.data.resolveTranslationOutputSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GallerySettingsSnapshotTest {

    @Test
    fun `task snapshot freezes routing settings without copying secrets`() {
        val cases = listOf(
            Settings(
                apiKey = "openai-secret",
                sourceLang = "ja",
                targetLang = "zh-CN",
                ocrEngine = OcrEngineKind.PADDLE_ONNX,
                translatorEngine = TranslatorEngine.OPENAI,
            ),
            Settings(
                baiduOcrApiKey = "baidu-key",
                baiduOcrSecretKey = "baidu-secret",
                baiduFanyiSecretKey = "fanyi-secret",
                sourceLang = "en",
                targetLang = "ja",
                ocrEngine = OcrEngineKind.BAIDU,
                translatorEngine = TranslatorEngine.BAIDU_FANYI,
            ),
        )
        val json = Json { encodeDefaults = true }

        cases.forEachIndexed { index, original ->
            val snapshot = TranslationPresetCatalog.fromSettings(
                id = "task-$index",
                name = "",
                shortName = "",
                settings = original,
            )
            val encoded = json.encodeToString(snapshot)
            assertFalse("case=$index OpenAI secret leaked", encoded.contains("openai-secret"))
            assertFalse("case=$index Baidu key leaked", encoded.contains("baidu-key"))
            assertFalse("case=$index Baidu secret leaked", encoded.contains("baidu-secret"))
            assertFalse("case=$index translator secret leaked", encoded.contains("fanyi-secret"))

            val currentCredentials = Settings(
                apiKey = "current-openai-secret",
                baiduOcrApiKey = "current-baidu-key",
                baiduOcrSecretKey = "current-baidu-secret",
                baiduFanyiSecretKey = "current-fanyi-secret",
            )
            val restored = snapshot.applyTo(currentCredentials)
            assertEquals("case=$index source", original.sourceLang, restored.sourceLang)
            assertEquals("case=$index target", original.targetLang, restored.targetLang)
            assertEquals("case=$index OCR", original.ocrEngine, restored.ocrEngine)
            assertEquals("case=$index translator", original.translatorEngine, restored.translatorEngine)
            assertEquals("case=$index API key", currentCredentials.apiKey, restored.apiKey)
            assertEquals(
                "case=$index OCR secret",
                currentCredentials.baiduOcrSecretKey,
                restored.baiduOcrSecretKey,
            )
        }
    }

    @Test
    fun `task snapshot table preserves translated image rendering settings`() {
        val cases = listOf(
            Settings(
                renderMode = RenderMode.BLOCKS,
                overlayStyleMode = OverlayStyleMode.FIXED,
                overlayTheme = OverlayTheme.CUSTOM,
                customBgColor = 0xCC102030.toInt(),
                customFgColor = 0xFFEEDDCC.toInt(),
                customBorderColor = 0xFFABCDEF.toInt(),
                customBorderWidth = 3,
                customBorderStyle = BorderStyle.DASHED,
                overlayTextSizeSp = 19,
                overlayTextStyle = OverlayTextStyle(
                    bold = true,
                    italic = true,
                    underline = true,
                    letterSpacingEm = 0.12f,
                    lineSpacingMultiplier = 1.4f,
                    alignment = OverlayTextAlignment.END,
                    strokeEnabled = true,
                    shadowEnabled = true,
                ),
                overlayAlpha = 0.63f,
                overlayFontFileName = "${"a".repeat(64)}.ttf",
                overlayPlacement = OverlayPlacement.ABOVE,
                overlayOffsetX = 17,
                overlayOffsetY = -9,
                overlayAllowWrap = false,
                overlayAvoidCollision = true,
                translationOutputFollowRecognition = false,
                translationOutputLayout = TranslationOutputLayout.VERTICAL,
                translationOutputDirection = TranslationOutputDirection.RIGHT_TO_LEFT,
            ),
            Settings(
                renderMode = RenderMode.BLOCKS,
                overlayStyleMode = OverlayStyleMode.ADAPTIVE,
                overlayFontFileName = "${"b".repeat(64)}.ttf",
                translationOutputFollowRecognition = true,
            ),
            Settings(
                renderMode = RenderMode.FLOATING_WINDOW,
                overlayStyleMode = OverlayStyleMode.FIXED,
            ),
        )

        cases.forEachIndexed { index, original ->
            val snapshot = TranslationPresetCatalog.fromSettings(
                id = "render-$index",
                name = "",
                shortName = "",
                settings = original,
            )
            val restored = snapshot.applyTo(Settings())
            val expectedOutput = resolveTranslationOutputSettings(
                original.translationOutputFollowRecognition,
                original.translationOutputLayout,
                original.translationOutputDirection,
            )
            assertEquals("case=$index render mode", original.renderMode, restored.renderMode)
            assertEquals("case=$index style mode", original.overlayStyleMode, restored.overlayStyleMode)
            assertEquals("case=$index theme", original.overlayTheme, restored.overlayTheme)
            assertEquals("case=$index background", original.customBgColor, restored.customBgColor)
            assertEquals("case=$index foreground", original.customFgColor, restored.customFgColor)
            assertEquals("case=$index border", original.customBorderColor, restored.customBorderColor)
            assertEquals("case=$index border width", original.customBorderWidth, restored.customBorderWidth)
            assertEquals("case=$index border style", original.customBorderStyle, restored.customBorderStyle)
            assertEquals("case=$index text size", original.overlayTextSizeSp, restored.overlayTextSizeSp)
            assertEquals("case=$index text style", original.overlayTextStyle, restored.overlayTextStyle)
            assertEquals("case=$index alpha", original.overlayAlpha, restored.overlayAlpha)
            assertEquals("case=$index font", original.overlayFontFileName, restored.overlayFontFileName)
            assertEquals("case=$index placement", original.overlayPlacement, restored.overlayPlacement)
            assertEquals("case=$index offset x", original.overlayOffsetX, restored.overlayOffsetX)
            assertEquals("case=$index offset y", original.overlayOffsetY, restored.overlayOffsetY)
            assertEquals("case=$index wrap", original.overlayAllowWrap, restored.overlayAllowWrap)
            assertEquals(
                "case=$index collision",
                original.overlayAvoidCollision,
                restored.overlayAvoidCollision,
            )
            assertEquals(
                "case=$index follow orientation",
                expectedOutput.followRecognition,
                restored.translationOutputFollowRecognition,
            )
            assertEquals(
                "case=$index output layout",
                expectedOutput.layout,
                restored.translationOutputLayout,
            )
            assertEquals(
                "case=$index output direction",
                expectedOutput.direction,
                restored.translationOutputDirection,
            )
        }
    }
}
