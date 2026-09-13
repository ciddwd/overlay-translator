package com.gameocr.app.service

internal enum class CaptureQuickSettingsAction {
    STOP_SERVICE,
    REQUEST_START,
}

/** Resolves a tile tap without duplicating the capture pipeline itself. */
internal fun resolveCaptureQuickSettingsAction(
    serviceRunning: Boolean,
): CaptureQuickSettingsAction = when {
    serviceRunning -> CaptureQuickSettingsAction.STOP_SERVICE
    else -> CaptureQuickSettingsAction.REQUEST_START
}
