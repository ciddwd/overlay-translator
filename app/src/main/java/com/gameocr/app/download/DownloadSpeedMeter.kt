package com.gameocr.app.download

/**
 * Smoothed transfer rate for progress display. A new file (byte count going backwards) restarts
 * the measurement instead of reporting a negative or stale rate.
 */
internal class DownloadSpeedMeter(private val windowMs: Long = 1_000L) {
    private var sampleAt = -1L
    private var sampleBytes = 0L
    var bytesPerSecond = 0L
        private set

    fun update(nowMs: Long, bytes: Long): Long {
        if (sampleAt < 0 || bytes < sampleBytes) {
            sampleAt = nowMs
            sampleBytes = bytes
            bytesPerSecond = 0L
            return 0L
        }
        val elapsed = nowMs - sampleAt
        if (elapsed >= windowMs) {
            val rate = (bytes - sampleBytes) * 1_000L / elapsed
            // Weighted toward the previous value so the number does not jump every update.
            bytesPerSecond = if (bytesPerSecond == 0L) rate else (bytesPerSecond * 7 + rate * 3) / 10
            sampleAt = nowMs
            sampleBytes = bytes
        }
        return bytesPerSecond
    }
}
