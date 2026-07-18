package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayStyleModeTest {

    @Test
    fun adaptiveAndManualLayoutPolicies_tableDriven_followBlockDisplayMode() {
        data class Case(
            val mode: OverlayStyleMode,
            val renderMode: RenderMode,
            val adaptiveActive: Boolean,
            val manualLayoutEnabled: Boolean,
        )

        val cases = listOf(
            Case(OverlayStyleMode.FIXED, RenderMode.BLOCKS, false, true),
            Case(OverlayStyleMode.FIXED, RenderMode.FLOATING_WINDOW, false, true),
            Case(OverlayStyleMode.ADAPTIVE, RenderMode.BLOCKS, true, false),
            Case(OverlayStyleMode.ADAPTIVE, RenderMode.FLOATING_WINDOW, false, true),
        )

        cases.forEach { case ->
            val label = "${case.mode} + ${case.renderMode}"
            assertEquals(
                label,
                case.adaptiveActive,
                adaptiveOverlayActive(case.mode, case.renderMode),
            )
            assertEquals(
                label,
                case.manualLayoutEnabled,
                manualOverlayLayoutControlsEnabled(case.mode, case.renderMode),
            )
        }
    }

    @Test
    fun defaults_preserveExistingFixedStyleBehavior() {
        assertEquals(OverlayStyleMode.FIXED, Settings().overlayStyleMode)
        assertEquals(OverlayStyleMode.FIXED, TranslationPreset(id = "id", name = "name").overlayStyleMode)
    }

    @Test
    fun effectiveOverlayRenderSettings_tableDriven_onlyLocksAdaptiveBlockLayout() {
        data class Case(
            val name: String,
            val source: Settings,
            val expectedPlacement: OverlayPlacement,
            val expectedOffsetX: Int,
            val expectedOffsetY: Int,
            val expectedWrap: Boolean,
            val expectedAvoidCollision: Boolean,
            val expectedTheme: OverlayTheme,
        )

        val customized = Settings(
            overlayStyleMode = OverlayStyleMode.ADAPTIVE,
            overlayPlacement = OverlayPlacement.BELOW,
            overlayOffsetX = 38,
            overlayOffsetY = -21,
            overlayAllowWrap = false,
            overlayAvoidCollision = true,
            overlayTheme = OverlayTheme.PAPER_LIGHT,
        )
        val cases = listOf(
            Case(
                name = "adaptive blocks use exact overlay layout",
                source = customized.copy(renderMode = RenderMode.BLOCKS),
                expectedPlacement = OverlayPlacement.OVERLAP,
                expectedOffsetX = 0,
                expectedOffsetY = 0,
                expectedWrap = true,
                expectedAvoidCollision = false,
                expectedTheme = OverlayTheme.CLASSIC_DARK,
            ),
            Case(
                name = "floating window keeps user style and layout",
                source = customized.copy(renderMode = RenderMode.FLOATING_WINDOW),
                expectedPlacement = OverlayPlacement.BELOW,
                expectedOffsetX = 38,
                expectedOffsetY = -21,
                expectedWrap = false,
                expectedAvoidCollision = true,
                expectedTheme = OverlayTheme.PAPER_LIGHT,
            ),
            Case(
                name = "fixed blocks keep user style and layout",
                source = customized.copy(
                    overlayStyleMode = OverlayStyleMode.FIXED,
                    renderMode = RenderMode.BLOCKS,
                ),
                expectedPlacement = OverlayPlacement.BELOW,
                expectedOffsetX = 38,
                expectedOffsetY = -21,
                expectedWrap = false,
                expectedAvoidCollision = true,
                expectedTheme = OverlayTheme.PAPER_LIGHT,
            ),
        )

        cases.forEach { case ->
            val actual = case.source.effectiveOverlayRenderSettings()
            assertEquals(case.name, case.expectedPlacement, actual.overlayPlacement)
            assertEquals(case.name, case.expectedOffsetX, actual.overlayOffsetX)
            assertEquals(case.name, case.expectedOffsetY, actual.overlayOffsetY)
            assertEquals(case.name, case.expectedWrap, actual.overlayAllowWrap)
            assertEquals(case.name, case.expectedAvoidCollision, actual.overlayAvoidCollision)
            assertEquals(case.name, case.expectedTheme, actual.overlayTheme)
        }
    }
}
