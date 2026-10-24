package com.gameocr.app.asr

import java.util.Locale

/** First partial to final includes the utterance itself; it is not local inference cost. */
internal class CloudSentenceTimings {
    private val starts = linkedMapOf<Long, Long>()
    fun start(id: Long, nowMs: Long) {
        starts.putIfAbsent(id, nowMs)
        while (starts.size > 256) starts.remove(starts.keys.first())
    }
    fun finish(id: Long, nowMs: Long): Long = (nowMs - (starts.remove(id) ?: nowMs)).coerceAtLeast(0L)
    fun reset() = starts.clear()
}

/** Count, total and maximum of one measured duration. */
internal class StageStats {
    var count = 0L
        private set
    var totalMs = 0L
        private set
    var maxMs = 0L
        private set

    fun add(ms: Long) {
        val value = ms.coerceAtLeast(0)
        count++
        totalMs += value
        if (value > maxMs) maxMs = value
    }

    val averageMs: Long get() = if (count == 0L) 0L else totalMs / count

    override fun toString(): String = "n=$count avg=${averageMs}ms max=${maxMs}ms"
}

/**
 * Per-stage timings for one speech session, logged as `[asr-metrics]`. Capture, recognition,
 * audio queueing and translation are measured separately: the audio queue absorbs short stalls,
 * but a recognition real-time factor that stays above 1 means recognition cannot keep up with
 * playback however large the buffer is. Thread-safe; transcripts and audio are never recorded.
 */
internal class SpeechMetrics(private val sampleRate: Int = PlaybackAudioCapture.TARGET_RATE) {
    private val lock = Any()
    private var capturedSamples = 0L
    private var droppedSamples = 0L
    private var captureGapMs = 0L
    private val queueWait = StageStats()
    private var maxBacklogSamples = 0
    private val finalDecode = StageStats()
    private var finalAudioSamples = 0L
    private val partialDecode = StageStats()
    private val cloudSentenceWindow = StageStats()
    private var partialAudioSamples = 0L
    private var skippedPartials = 0L
    private val translationWait = StageStats()
    private val translation = StageStats()
    private var translationFailures = 0L
    private var maxPendingTranslations = 0

    fun onCaptured(samples: Int, droppedForRoom: Int, gapMs: Long) = synchronized(lock) {
        capturedSamples += samples
        droppedSamples += droppedForRoom
        if (gapMs > 0) captureGapMs += gapMs
    }

    fun onDequeued(waitMs: Long, backlogSamples: Int) = synchronized(lock) {
        queueWait.add(waitMs)
        if (backlogSamples > maxBacklogSamples) maxBacklogSamples = backlogSamples
    }

    fun onDecode(audioSamples: Int, costMs: Long, final: Boolean) = synchronized(lock) {
        if (final) {
            finalDecode.add(costMs)
            finalAudioSamples += audioSamples
        } else {
            partialDecode.add(costMs)
            partialAudioSamples += audioSamples
        }
    }

    fun onPartialSkipped() = synchronized(lock) { skippedPartials++ }

    fun onCloudSentence(partialToFinalMs: Long) = synchronized(lock) { cloudSentenceWindow.add(partialToFinalMs) }

    fun onTranslation(waitMs: Long, costMs: Long, failed: Boolean, pending: Int) = synchronized(lock) {
        translationWait.add(waitMs)
        translation.add(costMs)
        if (failed) translationFailures++
        if (pending > maxPendingTranslations) maxPendingTranslations = pending
    }

    fun onTranslationQueued(pending: Int) = synchronized(lock) {
        if (pending > maxPendingTranslations) maxPendingTranslations = pending
    }

    data class Snapshot(
        val capturedSeconds: Float,
        val droppedSeconds: Float,
        val captureGapMs: Long,
        val queueWaitAvgMs: Long,
        val queueWaitMaxMs: Long,
        val maxBacklogSeconds: Float,
        /** Final decode time / audio decoded; above 1 recognition is slower than playback. */
        val finalRtf: Float,
        /** All decode time (final + partial previews) / captured audio. */
        val totalRtf: Float,
        val partialPasses: Long,
        val skippedPartials: Long,
        val translations: Long,
        val translationWaitAvgMs: Long,
        val translationAvgMs: Long,
        val translationMaxMs: Long,
        val translationFailures: Long,
        val maxPendingTranslations: Int,
        val cloudSentences: Long,
        val cloudPartialToFinalAvgMs: Long,
        val cloudPartialToFinalMaxMs: Long,
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        fun seconds(samples: Long) = samples.toFloat() / sampleRate
        fun rtf(costMs: Long, samples: Long) = if (samples == 0L) 0f else costMs / (seconds(samples) * 1000f)
        Snapshot(
            capturedSeconds = seconds(capturedSamples),
            droppedSeconds = seconds(droppedSamples),
            captureGapMs = captureGapMs,
            queueWaitAvgMs = queueWait.averageMs,
            queueWaitMaxMs = queueWait.maxMs,
            maxBacklogSeconds = seconds(maxBacklogSamples.toLong()),
            finalRtf = rtf(finalDecode.totalMs, finalAudioSamples),
            totalRtf = rtf(finalDecode.totalMs + partialDecode.totalMs, capturedSamples),
            partialPasses = partialDecode.count,
            skippedPartials = skippedPartials,
            translations = translation.count,
            translationWaitAvgMs = translationWait.averageMs,
            translationAvgMs = translation.averageMs,
            translationMaxMs = translation.maxMs,
            translationFailures = translationFailures,
            maxPendingTranslations = maxPendingTranslations,
            cloudSentences = cloudSentenceWindow.count,
            cloudPartialToFinalAvgMs = cloudSentenceWindow.averageMs,
            cloudPartialToFinalMaxMs = cloudSentenceWindow.maxMs,
        )
    }

    companion object {
        fun format(s: Snapshot): String = String.format(
            Locale.ROOT,
            "capture=%.1fs dropped=%.1fs gaps=%dms | queue wait avg=%dms max=%dms backlogMax=%.1fs | " +
                "asr finalRtf=%.2f totalRtf=%.2f partials=%d skipped=%d | " +
                "cloud n=%d partialToFinal avg=%dms max=%dms | " +
                "translate n=%d wait=%dms avg=%dms max=%dms failed=%d pendingMax=%d",
            s.capturedSeconds, s.droppedSeconds, s.captureGapMs,
            s.queueWaitAvgMs, s.queueWaitMaxMs, s.maxBacklogSeconds,
            s.finalRtf, s.totalRtf, s.partialPasses, s.skippedPartials,
            s.cloudSentences, s.cloudPartialToFinalAvgMs, s.cloudPartialToFinalMaxMs,
            s.translations, s.translationWaitAvgMs, s.translationAvgMs, s.translationMaxMs,
            s.translationFailures, s.maxPendingTranslations,
        )
    }
}
