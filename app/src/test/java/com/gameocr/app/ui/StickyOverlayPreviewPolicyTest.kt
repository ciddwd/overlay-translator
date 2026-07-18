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
            Case("preview has not reached top", previewTop = 180f, sectionBottom = 1200f, viewportTop = 100f, previewHeight = 160, expected = false),
            Case("preview reaches top", previewTop = 100f, sectionBottom = 1200f, viewportTop = 100f, previewHeight = 160, expected = true),
            Case("preview scrolled above top", previewTop = 20f, sectionBottom = 1200f, viewportTop = 100f, previewHeight = 160, expected = true),
            Case("section can no longer contain sticky preview", previewTop = 20f, sectionBottom = 250f, viewportTop = 100f, previewHeight = 160, expected = false),
            Case("section bottom exactly meets preview", previewTop = 20f, sectionBottom = 260f, viewportTop = 100f, previewHeight = 160, expected = false),
            Case("invalid preview height", previewTop = 20f, sectionBottom = 1200f, viewportTop = 100f, previewHeight = 0, expected = false),
            Case("invalid coordinate", previewTop = Float.NaN, sectionBottom = 1200f, viewportTop = 100f, previewHeight = 160, expected = false),
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
