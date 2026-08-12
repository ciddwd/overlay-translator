package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class TextRepairPatchCoveragePolicyTest {

    @Test
    fun canDisplay_tableDriven_rejectsOnlyMissingOrMinorityCoveragePatches() {
        data class Case(
            val name: String,
            val repairedPixels: Int,
            val residualPixels: Int,
            val hasPatchPixels: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("complete repair", 10_000, 0, true, true),
            Case("small diagnostic residual remains displayable", 104_953, 4_023, true, true),
            Case("equal repaired and residual keeps deterministic tie", 4_000, 4_000, true, true),
            Case("majority remains unresolved", 3_675, 42_817, true, false),
            Case("zero repaired pixels", 0, 0, true, false),
            Case("missing patch pixels", 10_000, 0, false, false),
            Case("negative diagnostic residual is normalized", 10, -1, true, true),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                TextRepairPatchCoveragePolicy.canDisplay(
                    repairedPixels = case.repairedPixels,
                    residualPixels = case.residualPixels,
                    hasPatchPixels = case.hasPatchPixels,
                ),
            )
        }
    }
}
