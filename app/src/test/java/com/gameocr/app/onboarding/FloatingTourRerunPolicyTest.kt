package com.gameocr.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingTourRerunPolicyTest {
    @Test
    fun helpAndOnboardingExit_areTableDriven() {
        assertEquals(
            FloatingTourRerunDecision(
                resetCompletion = true,
                notifyRunningService = false,
            ),
            FloatingTourRerunPolicy.onHelpOpened(),
        )

        data class Case(
            val name: String,
            val openedFromHelp: Boolean,
            val completed: Boolean,
            val serviceRunning: Boolean,
            val expectedNotify: Boolean,
        )
        val cases = listOf(
            Case("help-completed-service-running", true, true, true, true),
            Case("help-completed-service-stopped", true, true, false, false),
            Case("help-skipped-service-running", true, false, true, false),
            Case("first-run-completed-service-running", false, true, true, false),
            Case("ordinary-skip-service-stopped", false, false, false, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                FloatingTourRerunDecision(
                    resetCompletion = false,
                    notifyRunningService = case.expectedNotify,
                ),
                FloatingTourRerunPolicy.onOnboardingExit(
                    openedFromHelp = case.openedFromHelp,
                    completed = case.completed,
                    captureServiceRunning = case.serviceRunning,
                ),
            )
        }
    }
}
