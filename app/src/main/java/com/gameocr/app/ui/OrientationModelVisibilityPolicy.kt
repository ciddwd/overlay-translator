package com.gameocr.app.ui

/**
 * The optional ONNX orientation package remains implemented for existing installations and a
 * possible future opt-in flow, but it is intentionally hidden from user-facing model management.
 * OCR geometry fallback continues to work when the package is absent.
 */
internal object OrientationModelVisibilityPolicy {
    const val userManagementVisible: Boolean = false

    fun shouldReportMissingForPreset(
        textOrientationAutoDetect: Boolean,
        modelReady: Boolean,
    ): Boolean = userManagementVisible && textOrientationAutoDetect && !modelReady
}
