package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagePickerRowTapPolicyTest {

    @Test
    fun rowTapAction_tableDriven_routesEnabledConflictAndUnavailableRows() {
        data class Case(
            val name: String,
            val disabled: Boolean,
            val hasDisabledAction: Boolean,
            val expected: LanguagePickerRowTapAction,
        )

        listOf(
            Case(
                name = "enabled row selects normally",
                disabled = false,
                hasDisabledAction = false,
                expected = LanguagePickerRowTapAction.SELECT,
            ),
            Case(
                name = "enabled row ignores the disabled callback",
                disabled = false,
                hasDisabledAction = true,
                expected = LanguagePickerRowTapAction.SELECT,
            ),
            Case(
                name = "disabled conflict row opens its explanation",
                disabled = true,
                hasDisabledAction = true,
                expected = LanguagePickerRowTapAction.DISABLED_ACTION,
            ),
            Case(
                name = "other disabled row remains unavailable",
                disabled = true,
                hasDisabledAction = false,
                expected = LanguagePickerRowTapAction.NONE,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                languagePickerRowTapAction(case.disabled, case.hasDisabledAction),
            )
        }
    }
}
