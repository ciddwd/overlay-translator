package com.gameocr.app.overlay

import com.gameocr.app.data.OverlayStyleMode
import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.effectiveOverlayRenderSettings
import com.gameocr.app.data.floatingWindowRenderSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowAppearanceTest {
    private val user = Settings(
        overlayTextSizeSp = 22,
        overlayAlpha = 0.6f,
        overlayTheme = OverlayTheme.PAPER_LIGHT,
        overlayTextStyle = OverlayTextStyle().copy(lineSpacingMultiplier = 1.6f),
    )
    private val expected = FloatingWindowAppearance(22, 0.6f, OverlayTheme.PAPER_LIGHT, user.overlayTextStyle.normalized())

    @Test fun floatingWindowAlwaysUsesTheUsersAppearance_tableDriven() {
        data class Case(val styleMode: OverlayStyleMode, val renderMode: RenderMode)
        listOf(
            // Adaptive blocks override OCR blocks only; speech in the shared window keeps the user's look.
            Case(OverlayStyleMode.ADAPTIVE, RenderMode.BLOCKS),
            Case(OverlayStyleMode.FIXED, RenderMode.BLOCKS),
            Case(OverlayStyleMode.ADAPTIVE, RenderMode.FLOATING_WINDOW),
            Case(OverlayStyleMode.FIXED, RenderMode.FLOATING_WINDOW),
        ).forEach { case ->
            val settings = user.copy(overlayStyleMode = case.styleMode, renderMode = case.renderMode)
            assertEquals(case.toString(), expected, FloatingWindowAppearance.from(settings.floatingWindowRenderSettings()))
        }
    }

    @Test fun adaptiveBlocksStillOverrideTheBlockAppearance() {
        val blocks = user.copy(overlayStyleMode = OverlayStyleMode.ADAPTIVE, renderMode = RenderMode.BLOCKS)
            .effectiveOverlayRenderSettings()
        assertEquals(14, blocks.overlayTextSizeSp)
        assertEquals(1f, blocks.overlayAlpha)
        assertEquals(OverlayTheme.CLASSIC_DARK, blocks.overlayTheme)
    }
}
