package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayAccessibilityLabelsTest {

    @Test
    fun actionWithState_joinsActionStateAndHint() {
        assertEquals(
            "Floating button. Loop off. Double tap to run",
            OverlayAccessibilityLabels.actionWithState(
                action = "Floating button",
                state = "Loop off",
                hint = "Double tap to run"
            )
        )
    }

    @Test
    fun option_usesSelectedOrActionHint() {
        data class Case(
            val name: String,
            val selected: Boolean,
            val expected: String
        )

        val cases = listOf(
            Case(
                name = "selected",
                selected = true,
                expected = "Japanese. ja. Selected for source"
            ),
            Case(
                name = "not-selected",
                selected = false,
                expected = "Japanese. ja. Set as source"
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                OverlayAccessibilityLabels.option(
                    title = "Japanese",
                    detail = "ja",
                    selected = case.selected,
                    selectedLabel = "Selected for source",
                    actionHint = "Set as source"
                )
            )
        }
    }

    @Test
    fun slot_readsCurrentValueAndSelectionState() {
        assertEquals(
            "Source: Auto. Editing",
            OverlayAccessibilityLabels.slot(
                label = "Source",
                value = "Auto",
                selected = true,
                selectedLabel = "Editing",
                actionHint = "Double tap to edit"
            )
        )
    }

    @Test
    fun regionPicker_readsDrawingOrSelectedSize() {
        data class Case(
            val name: String,
            val width: Int?,
            val height: Int?,
            val expected: String
        )

        val cases = listOf(
            Case("drawing", null, null, "Region picker. Drag to draw"),
            Case("adjusting", 320, 180, "Region picker. 320 x 180. Drag to adjust")
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                OverlayAccessibilityLabels.regionPicker(
                    title = "Region picker",
                    width = case.width,
                    height = case.height,
                    drawingHint = "Drag to draw",
                    adjustingHint = "Drag to adjust"
                )
            )
        }
    }
}
