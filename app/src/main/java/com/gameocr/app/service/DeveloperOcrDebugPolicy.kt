package com.gameocr.app.service

internal object DeveloperOcrDebugPolicy {
    fun redBoxActive(
        developerOptionsEnabled: Boolean,
        ocrRedBoxModeEnabled: Boolean,
    ): Boolean = developerOptionsEnabled && ocrRedBoxModeEnabled

    fun shouldTranslate(
        developerOptionsEnabled: Boolean,
        ocrRedBoxModeEnabled: Boolean,
        showTranslation: Boolean,
    ): Boolean = !redBoxActive(developerOptionsEnabled, ocrRedBoxModeEnabled) || showTranslation
}
