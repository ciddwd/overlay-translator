package com.gameocr.app.capture

/** At most one light tick per processing capture; observations and manual actions stay quiet. */
internal class LoopCaptureFeedbackPolicy {
    private var lastCaptureId: Long? = null

    @Synchronized
    fun accept(enabled: Boolean, loopActive: Boolean, captureId: Long): Boolean {
        if (!enabled || !loopActive || lastCaptureId == captureId) return false
        lastCaptureId = captureId
        return true
    }

    @Synchronized
    fun reset() { lastCaptureId = null }
}
