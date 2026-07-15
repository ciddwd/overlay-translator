package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StickyOverlayPreviewPolicyTest {

    @Test
    fun shouldStick_coversApproachPinnedAndSectionExitCases() {
        data class Case(
            val name: String,
            val previewTop: Float,
            val sectionBottom: Float,
            val viewportTop: Float,
            val previewHeight: Int,
            val expected: Boolean,
        )

        listOf(
            Case("preview has not reached top", 180f, 1200f, 100f, 160, false),
            Case("preview reaches top", 100f, 1200f, 100f, 160, true),
            Case("preview scrolled above top", 20f, 1200f, 100f, 160, true),
            Case("section can no longer contain sticky preview", 20f, 250f, 100f, 160, false),
            Case("section bottom exactly meets preview", 20f, 260f, 100f, 160, false),
            Case("invalid preview height", 20f, 1200f, 100f, 0, false),
            Case("invalid coordinate", Float.NaN, 1200f, 100f, 160, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                StickyOverlayPreviewPolicy.shouldStick(
                    previewTopInWindow = case.previewTop,
                    sectionBottomInWindow = case.sectionBottom,
                    viewportTopInWindow = case.viewportTop,
                    previewHeightPx = case.previewHeight,
                ),
            )
        }
    }
}
