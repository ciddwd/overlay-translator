package com.gameocr.app.onboarding

internal data class FloatingTourRerunDecision(
    val resetCompletion: Boolean,
    val notifyRunningService: Boolean,
)

internal object FloatingTourRerunPolicy {
    fun onHelpOpened(): FloatingTourRerunDecision =
        FloatingTourRerunDecision(
            resetCompletion = true,
            notifyRunningService = false,
        )

    fun onOnboardingExit(
        openedFromHelp: Boolean,
        completed: Boolean,
        captureServiceRunning: Boolean,
    ): FloatingTourRerunDecision =
        FloatingTourRerunDecision(
            resetCompletion = false,
            notifyRunningService =
                openedFromHelp && completed && captureServiceRunning,
        )
}
