package com.gameocr.app.service

internal object DeveloperOcrDebugPolicy {
    fun shouldShowNoTextHint(
        developerOptionsEnabled: Boolean,
        hideOcrNoTextHint: Boolean,
    ): Boolean = !(developerOptionsEnabled && hideOcrNoTextHint)

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
