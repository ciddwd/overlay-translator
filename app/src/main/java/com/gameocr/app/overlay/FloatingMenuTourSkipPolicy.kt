package com.gameocr.app.overlay

internal enum class FloatingMenuTourSkipEvent {
    REQUEST_SKIP,
    CONTINUE_TOUR,
    CONFIRM_SKIP,
}

internal enum class FloatingMenuTourSkipAction {
    SHOW_CONFIRMATION,
    RESUME_TOUR,
    COMPLETE_TOUR,
    IGNORE,
}

internal object FloatingMenuTourSkipPolicy {
    fun action(
        confirmationVisible: Boolean,
        event: FloatingMenuTourSkipEvent,
    ): FloatingMenuTourSkipAction = when {
        !confirmationVisible && event == FloatingMenuTourSkipEvent.REQUEST_SKIP ->
            FloatingMenuTourSkipAction.SHOW_CONFIRMATION
        confirmationVisible && event == FloatingMenuTourSkipEvent.CONTINUE_TOUR ->
            FloatingMenuTourSkipAction.RESUME_TOUR
        confirmationVisible && event == FloatingMenuTourSkipEvent.CONFIRM_SKIP ->
            FloatingMenuTourSkipAction.COMPLETE_TOUR
        else -> FloatingMenuTourSkipAction.IGNORE
    }
}
