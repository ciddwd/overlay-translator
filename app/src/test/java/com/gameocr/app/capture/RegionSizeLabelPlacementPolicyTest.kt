package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RegionSizeLabelPlacementPolicyTest {
    @Test
    fun placement_cases() {
        data class Case(
            val name: String,
            val selection: RegionSizeLabelRect,
            val expected: RegionSizeLabelRect?,
        )

        val cases = listOf(
            Case("center uses above", rect(300, 300, 500, 500), rect(350, 246, 450, 296)),
            Case("top edge uses below", rect(300, 10, 500, 210), rect(350, 214, 450, 264)),
            Case("bottom edge uses above", rect(300, 590, 500, 790), rect(350, 536, 450, 586)),
            Case("full height uses right", rect(200, 4, 500, 796), rect(504, 375, 604, 425)),
            Case("almost full viewport hides", rect(4, 4, 796, 796), null),
        )

        cases.forEach { case ->
            val actual = RegionSizeLabelPlacementPolicy.place(
                selection = case.selection,
                viewportWidth = 800,
                viewportHeight = 800,
                labelWidth = 100,
                labelHeight = 50,
                gap = 4,
                margin = 4,
            )
            assertEquals(case.name, case.expected, actual)
            if (actual != null) assertFalse(case.name, actual.intersects(case.selection))
        }
    }

    @Test
    fun invalid_or_oversized_label_isHidden() {
        val selection = rect(100, 100, 200, 200)
        listOf(
            0 to 100,
            100 to 0,
            900 to 40,
        ).forEach { (width, height) ->
            assertNull(
                RegionSizeLabelPlacementPolicy.place(
                    selection, 800, 800, width, height, gap = 4, margin = 4
                )
            )
        }
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        RegionSizeLabelRect(left, top, right, bottom)
}
