package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SharePromptPolicyTest {

    @Test
    fun mainScreenEntryDecision_isTableDriven() {
        data class Case(
            val name: String,
            val storedCount: Int,
            val alreadyShown: Boolean,
            val expectedCount: Int,
            val expectedEligible: Boolean,
        )

        listOf(
            Case("first main entry", 0, false, 1, false),
            Case("second main entry", 1, false, 2, false),
            Case("third main entry", 2, false, 3, true),
            Case("eligible prompt deferred to next session", 3, false, 3, true),
            Case("accepted prompt never returns", 3, true, 3, false),
            Case("declined prompt never returns", 2, true, 2, false),
            Case("negative corrupted count is normalized", -4, false, 1, false),
            Case("oversized corrupted count cannot overflow", Int.MAX_VALUE, false, 3, true),
        ).forEach { case ->
            val result = SharePromptPolicy.onMainScreenEntry(
                storedEntryCount = case.storedCount,
                promptAlreadyShown = case.alreadyShown,
            )
            assertEquals(case.name, case.expectedCount, result.nextEntryCount)
            assertEquals(case.name, case.expectedEligible, result.eligibleToShow)
        }
    }
}
