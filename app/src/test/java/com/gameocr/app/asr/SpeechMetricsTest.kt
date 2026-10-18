package com.gameocr.app.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMetricsTest {
    @Test fun cloudSentenceWindowsDoNotInflateLocalInferenceRates() {
        val metrics = SpeechMetrics(sampleRate = 1000)
        metrics.onCaptured(4000, 0, 0)
        metrics.onCloudSentence(1800)
        metrics.onCloudSentence(2200)
        val s = metrics.snapshot()
        assertEquals(0f, s.totalRtf)
        assertEquals(0f, s.finalRtf)
        assertEquals(2L, s.cloudSentences)
        assertEquals(2000L, s.cloudPartialToFinalAvgMs)
        assertEquals(2200L, s.cloudPartialToFinalMaxMs)
        assertTrue(SpeechMetrics.format(s).contains("cloud n=2 partialToFinal avg=2000ms max=2200ms"))
    }

    @Test fun cloudSentenceTimingHandlesRevisionsInterleavingAndReset_tableDriven() {
        val timing = CloudSentenceTimings()
        timing.start(1, 100)
        timing.start(1, 300) // Revision must not restart the clock.
        timing.start(2, 200)
        assertEquals(600L, timing.finish(1, 700))
        assertEquals(600L, timing.finish(2, 800))
        assertEquals(0L, timing.finish(3, 900)) // Final without partial.
        timing.start(4, 1000)
        timing.reset()
        assertEquals(0L, timing.finish(4, 1500))
        timing.start(5, 2000)
        assertEquals(0L, timing.finish(5, 1900))
        for (id in 10L..266L) timing.start(id, 3000)
        assertEquals(0L, timing.finish(10, 4000)) // Bounded state.
        assertEquals(1000L, timing.finish(266, 4000))
    }
    @Test fun stagesAreMeasuredSeparately() {
        val metrics = SpeechMetrics(sampleRate = 1_000)
        metrics.onCaptured(samples = 2_000, droppedForRoom = 500, gapMs = 30)
        metrics.onCaptured(samples = 2_000, droppedForRoom = 0, gapMs = -5)
        metrics.onDequeued(waitMs = 10, backlogSamples = 1_500)
        metrics.onDequeued(waitMs = 30, backlogSamples = 200)
        metrics.onDecode(audioSamples = 2_000, costMs = 1_000, final = true)
        metrics.onDecode(audioSamples = 1_000, costMs = 1_000, final = false)
        metrics.onPartialSkipped()
        metrics.onTranslationQueued(pending = 3)
        metrics.onTranslation(waitMs = 100, costMs = 400, failed = false, pending = 1)
        metrics.onTranslation(waitMs = 300, costMs = 800, failed = true, pending = 0)

        val s = metrics.snapshot()
        assertEquals(4f, s.capturedSeconds)
        assertEquals(0.5f, s.droppedSeconds)
        assertEquals(30L, s.captureGapMs)
        assertEquals(20L, s.queueWaitAvgMs)
        assertEquals(30L, s.queueWaitMaxMs)
        assertEquals(1.5f, s.maxBacklogSeconds)
        // 1 s to decode 2 s of finished speech: faster than playback.
        assertEquals(0.5f, s.finalRtf)
        // Partial previews double the work over 4 s of captured audio.
        assertEquals(0.5f, s.totalRtf)
        assertEquals(1L, s.partialPasses)
        assertEquals(1L, s.skippedPartials)
        assertEquals(2L, s.translations)
        assertEquals(200L, s.translationWaitAvgMs)
        assertEquals(600L, s.translationAvgMs)
        assertEquals(800L, s.translationMaxMs)
        assertEquals(1L, s.translationFailures)
        assertEquals(3, s.maxPendingTranslations)
    }

    @Test fun emptySessionHasZeroRatesAndFormatsWithoutTranscriptText() {
        val s = SpeechMetrics().snapshot()
        assertEquals(0f, s.finalRtf)
        assertEquals(0f, s.totalRtf)
        val line = SpeechMetrics.format(s)
        listOf("capture=", "queue wait", "asr finalRtf=", "translate n=").forEach { assertTrue(it, line.contains(it)) }
    }
}
