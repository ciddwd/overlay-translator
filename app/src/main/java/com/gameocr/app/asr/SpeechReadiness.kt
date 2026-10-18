package com.gameocr.app.asr

/** Called only under the controller's command mutex. Each capture run announces readiness once. */
internal class SpeechReadiness {
    private var generation = -1L
    private var capture = false
    private var engine = false
    private var announced = false

    fun begin(generation: Long, remote: Boolean) {
        this.generation = generation
        capture = false
        engine = !remote
        announced = false
    }

    fun invalidate() { generation = -1L }

    fun captureStarted(generation: Long): Boolean = update(generation, true)
    fun engineConfirmed(generation: Long): Boolean = update(generation, false)

    private fun update(value: Long, isCapture: Boolean): Boolean {
        if (value < 0 || value != generation || announced) return false
        if (isCapture) capture = true else engine = true
        return (capture && engine).also { if (it) announced = true }
    }
}
