package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class TextRepairPatchCoveragePolicyTest {

    @Test
    fun canDisplay_tableDriven_requiresCompleteEraseTargetCoverage() {
        data class Case(
            val name: String,
            val repairedPixels: Int,
            val residualPixels: Int,
            val hasPatchPixels: Boolean,
            val expected: Boolean,
            val requiredCoveragePixels: Int = 0,
            val repairedRequiredCoveragePixels: Int = requiredCoveragePixels,
            val completionReliable: Boolean = true,
        )

        val cases = listOf(
            Case("complete repair", 10_000, 0, true, true),
            Case("detected residual keeps fallback eligible", 104_953, 4_023, true, false),
            Case("equal repaired and residual is incomplete", 4_000, 4_000, true, false),
            Case("majority remains unresolved", 3_675, 42_817, true, false),
            Case("zero repaired pixels", 0, 0, true, false),
            Case("missing patch pixels", 10_000, 0, false, false),
            Case("invalid residual cannot prove completion", 10, -1, true, false),
            Case(
                name = "partial erase coverage cannot suppress fallback",
                repairedPixels = 8_000,
                residualPixels = 0,
                hasPatchPixels = true,
                expected = false,
                requiredCoveragePixels = 10_000,
                repairedRequiredCoveragePixels = 8_000,
            ),
            Case(
                name = "unreliable completion cannot suppress fallback",
                repairedPixels = 10_000,
                residualPixels = 0,
                hasPatchPixels = true,
                expected = false,
                requiredCoveragePixels = 10_000,
                repairedRequiredCoveragePixels = 10_000,
                completionReliable = false,
            ),
            Case(
                name = "complete erase mask is displayable",
                repairedPixels = 10_000,
                residualPixels = 0,
                hasPatchPixels = true,
                expected = true,
                requiredCoveragePixels = 10_000,
                repairedRequiredCoveragePixels = 10_000,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                TextRepairPatchCoveragePolicy.canDisplay(
                    repairedPixels = case.repairedPixels,
                    residualPixels = case.residualPixels,
                    hasPatchPixels = case.hasPatchPixels,
                    requiredCoveragePixels = case.requiredCoveragePixels,
                    repairedRequiredCoveragePixels = case.repairedRequiredCoveragePixels,
                    completionReliable = case.completionReliable,
                ),
            )
        }
    }
}
