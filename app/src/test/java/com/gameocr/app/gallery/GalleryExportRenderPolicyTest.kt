package com.gameocr.app.gallery

import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationOutputDirection
import com.gameocr.app.data.TranslationOutputLayout
import com.gameocr.app.ocr.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryExportRenderPolicyTest {

    @Test
    fun `render mode table covers all display mode combinations`() {
        data class Case(
            val renderMode: RenderMode,
            val styleMode: OverlayStyleMode,
            val expected: GalleryExportRenderMode,
        )

        listOf(
            Case(
                RenderMode.BLOCKS,
                OverlayStyleMode.FIXED,
                GalleryExportRenderMode.FIXED_BLOCKS,
            ),
            Case(
                RenderMode.BLOCKS,
                OverlayStyleMode.ADAPTIVE,
                GalleryExportRenderMode.ADAPTIVE_BLOCKS,
            ),
            Case(
                RenderMode.FLOATING_WINDOW,
                OverlayStyleMode.FIXED,
                GalleryExportRenderMode.UNSUPPORTED_FLOATING,
            ),
            Case(
                RenderMode.FLOATING_WINDOW,
                OverlayStyleMode.ADAPTIVE,
                GalleryExportRenderMode.UNSUPPORTED_FLOATING,
            ),
        ).forEach { case ->
            assertEquals(
                "${case.renderMode} + ${case.styleMode}",
                case.expected,
                galleryExportRenderMode(case.renderMode, case.styleMode),
            )
        }
    }

    @Test
    fun `export availability rejects floating mode after normal task gating`() {
        data class Case(
            val name: String,
            val status: GalleryTaskStatus,
            val successes: Int,
            val mode: GalleryExportRenderMode,
            val expected: Boolean,
        )

        listOf(
            Case(
                "fixed terminal task",
                GalleryTaskStatus.SUCCEEDED,
                1,
                GalleryExportRenderMode.FIXED_BLOCKS,
                true,
            ),
            Case(
                "adaptive terminal task",
                GalleryTaskStatus.PARTIAL,
                1,
                GalleryExportRenderMode.ADAPTIVE_BLOCKS,
                true,
            ),
            Case(
                "floating terminal task",
                GalleryTaskStatus.SUCCEEDED,
                1,
                GalleryExportRenderMode.UNSUPPORTED_FLOATING,
                false,
            ),
            Case(
                "active fixed task",
                GalleryTaskStatus.RUNNING,
                1,
                GalleryExportRenderMode.FIXED_BLOCKS,
                false,
            ),
            Case(
                "empty fixed task",
                GalleryTaskStatus.SUCCEEDED,
                0,
                GalleryExportRenderMode.FIXED_BLOCKS,
                false,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryCanExport(case.status, case.successes, case.mode),
            )
        }
    }

    @Test
    fun `orientation table follows recognition or explicit output settings`() {
        data class Case(
            val name: String,
            val settings: Settings,
            val stored: String?,
            val expected: TextOrientation,
        )

        val manualHorizontalRtl = Settings(
            translationOutputFollowRecognition = false,
            translationOutputLayout = TranslationOutputLayout.HORIZONTAL,
            translationOutputDirection = TranslationOutputDirection.RIGHT_TO_LEFT,
        )
        val manualVerticalLtr = Settings(
            translationOutputFollowRecognition = false,
            translationOutputLayout = TranslationOutputLayout.VERTICAL,
            translationOutputDirection = TranslationOutputDirection.LEFT_TO_RIGHT,
        )
        listOf(
            Case(
                "follow horizontal ltr",
                Settings(),
                TextOrientation.HORIZONTAL_LTR.name,
                TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                "follow horizontal rtl",
                Settings(),
                TextOrientation.HORIZONTAL_RTL.name,
                TextOrientation.HORIZONTAL_RTL,
            ),
            Case(
                "follow vertical rtl",
                Settings(),
                TextOrientation.VERTICAL_RTL.name,
                TextOrientation.VERTICAL_RTL,
            ),
            Case(
                "follow vertical ltr",
                Settings(),
                TextOrientation.VERTICAL_LTR.name,
                TextOrientation.VERTICAL_LTR,
            ),
            Case(
                "missing orientation falls back safely",
                Settings(),
                null,
                TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                "invalid orientation falls back safely",
                Settings(),
                "SIDEWAYS",
                TextOrientation.HORIZONTAL_LTR,
            ),
            Case(
                "manual horizontal rtl overrides stored vertical",
                manualHorizontalRtl,
                TextOrientation.VERTICAL_LTR.name,
                TextOrientation.HORIZONTAL_RTL,
            ),
            Case(
                "manual vertical ltr overrides stored horizontal",
                manualVerticalLtr,
                TextOrientation.HORIZONTAL_RTL.name,
                TextOrientation.VERTICAL_LTR,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryExportOrientation(case.settings, case.stored),
            )
        }
    }

    @Test
    fun `vertical placement always overlaps while horizontal keeps the setting`() {
        data class Case(
            val requested: OverlayPlacement,
            val orientation: TextOrientation,
            val expected: OverlayPlacement,
        )

        listOf(
            Case(
                OverlayPlacement.BELOW,
                TextOrientation.HORIZONTAL_LTR,
                OverlayPlacement.BELOW,
            ),
            Case(
                OverlayPlacement.ABOVE,
                TextOrientation.HORIZONTAL_RTL,
                OverlayPlacement.ABOVE,
            ),
            Case(
                OverlayPlacement.BELOW,
                TextOrientation.VERTICAL_RTL,
                OverlayPlacement.OVERLAP,
            ),
            Case(
                OverlayPlacement.ABOVE,
                TextOrientation.VERTICAL_LTR,
                OverlayPlacement.OVERLAP,
            ),
        ).forEach { case ->
            assertEquals(
                "${case.requested} + ${case.orientation}",
                case.expected,
                galleryExportPlacement(case.requested, case.orientation),
            )
        }
    }

    @Test
    fun `theme palette table matches block overlay colors and borders`() {
        data class Case(
            val theme: OverlayTheme,
            val expectedBackground: Int,
            val expectedForeground: Int,
            val expectedBorder: Int,
            val expectedWidth: Int,
        )

        listOf(
            Case(
                OverlayTheme.CLASSIC_DARK,
                0xE6000000.toInt(),
                0xFFFFFFFF.toInt(),
                0x00000000,
                0,
            ),
            Case(
                OverlayTheme.AMBER_GOLD,
                0xF0241608.toInt(),
                0xFFFFD27F.toInt(),
                0xFFB8860B.toInt(),
                2,
            ),
            Case(
                OverlayTheme.PAPER_LIGHT,
                0xF0F5EFE0.toInt(),
                0xFF3E2A1F.toInt(),
                0xFFB68850.toInt(),
                1,
            ),
            Case(
                OverlayTheme.FROST_GLASS,
                0xCC1E293B.toInt(),
                0xFFE0F2FE.toInt(),
                0xFF60A5FA.toInt(),
                1,
            ),
        ).forEach { case ->
            val palette = galleryExportPalette(
                settings = Settings(
                    overlayTheme = case.theme,
                    overlayAlpha = 1f,
                ),
                density = 3f,
                renderScale = 1f,
            )
            assertEquals("${case.theme} background", case.expectedBackground, palette.backgroundColor)
            assertEquals("${case.theme} foreground", case.expectedForeground, palette.foregroundColor)
            assertEquals("${case.theme} border", case.expectedBorder, palette.borderColor)
            assertEquals("${case.theme} width", case.expectedWidth, palette.borderWidthPx)
            assertEquals("${case.theme} style", BorderStyle.SOLID, palette.borderStyle)
        }
    }

    @Test
    fun `custom palette applies density scale alpha and border style`() {
        data class Case(
            val name: String,
            val alpha: Float,
            val expectedBackground: Int,
            val expectedForeground: Int,
            val expectedBorder: Int,
        )

        listOf(
            Case("opaque", 1f, 0x80442211.toInt(), 0xFF112233.toInt(), 0xC0556677.toInt()),
            Case("half", 0.5f, 0x40442211, 0x80112233.toInt(), 0x60556677),
            Case("transparent", 0f, 0x00442211, 0x00112233, 0x00556677),
        ).forEach { case ->
            val palette = galleryExportPalette(
                settings = Settings(
                    overlayTheme = OverlayTheme.CUSTOM,
                    overlayAlpha = case.alpha,
                    customBgColor = 0x80442211.toInt(),
                    customFgColor = 0xFF112233.toInt(),
                    customBorderColor = 0xC0556677.toInt(),
                    customBorderWidth = 2,
                    customBorderStyle = BorderStyle.DASHED,
                ),
                density = 2f,
                renderScale = 1.5f,
            )
            assertEquals("${case.name} background", case.expectedBackground, palette.backgroundColor)
            assertEquals("${case.name} foreground", case.expectedForeground, palette.foregroundColor)
            assertEquals("${case.name} border", case.expectedBorder, palette.borderColor)
            assertEquals("${case.name} width", 6, palette.borderWidthPx)
            assertEquals("${case.name} style", BorderStyle.DASHED, palette.borderStyle)
        }
    }
}
