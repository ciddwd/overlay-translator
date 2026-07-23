package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMenuTourSkipPolicyTest {
    @Test
    fun action_requiresVisibleConfirmationBeforeCompleting_tableDriven() {
        data class Case(
            val name: String,
            val confirmationVisible: Boolean,
            val event: FloatingMenuTourSkipEvent,
            val expected: FloatingMenuTourSkipAction,
        )

        val cases = listOf(
            Case(
                name = "first-skip-opens-confirmation",
                confirmationVisible = false,
                event = FloatingMenuTourSkipEvent.REQUEST_SKIP,
                expected = FloatingMenuTourSkipAction.SHOW_CONFIRMATION,
            ),
            Case(
                name = "continue-resumes-only-from-confirmation",
                confirmationVisible = true,
                event = FloatingMenuTourSkipEvent.CONTINUE_TOUR,
                expected = FloatingMenuTourSkipAction.RESUME_TOUR,
            ),
            Case(
                name = "confirm-completes-only-from-confirmation",
                confirmationVisible = true,
                event = FloatingMenuTourSkipEvent.CONFIRM_SKIP,
                expected = FloatingMenuTourSkipAction.COMPLETE_TOUR,
            ),
            Case(
                name = "confirm-without-dialog-is-ignored",
                confirmationVisible = false,
                event = FloatingMenuTourSkipEvent.CONFIRM_SKIP,
                expected = FloatingMenuTourSkipAction.IGNORE,
            ),
            Case(
                name = "duplicate-skip-on-dialog-is-ignored",
                confirmationVisible = true,
                event = FloatingMenuTourSkipEvent.REQUEST_SKIP,
                expected = FloatingMenuTourSkipAction.IGNORE,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                FloatingMenuTourSkipPolicy.action(
                    confirmationVisible = case.confirmationVisible,
                    event = case.event,
                ),
            )
        }
    }
}
