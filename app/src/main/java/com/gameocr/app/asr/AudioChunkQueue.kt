package com.gameocr.app.asr

/**
 * Bounded hand-off between the capture thread and the inference thread. Capture never waits for
 * recognition: when the queue is full the oldest audio is dropped and the next chunk taken is
 * marked [Chunk.discontinuity], so the engine restarts its utterance instead of joining speech
 * across the gap. A queue serves one capture run; [close] wakes a blocked [take].
 */
internal class AudioChunkQueue(private val maxSamples: Int) {
    class Chunk(
        val samples: FloatArray,
        val capturedAtMs: Long,
        /** Audio before this chunk was dropped or skipped; do not join it to earlier audio. */
        val discontinuity: Boolean,
    )

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    private var queuedSamples = 0
    private var gapPending = false
    private var closed = false

    /** Total samples dropped because inference fell further behind than the bound. */
    var droppedSamples: Long = 0L
        private set

    val backlogSamples: Int get() = synchronized(lock) { queuedSamples }

    /** Returns the number of samples dropped to make room (0 when nothing was dropped). */
    fun offer(samples: FloatArray, capturedAtMs: Long): Int = synchronized(lock) {
        if (closed || samples.isEmpty()) return 0
        var dropped = 0
        while (chunks.isNotEmpty() && queuedSamples + samples.size > maxSamples) {
            val oldest = chunks.removeFirst()
            queuedSamples -= oldest.samples.size
            dropped += oldest.samples.size
        }
        if (dropped > 0) {
            droppedSamples += dropped
            // The gap sits before the oldest audio still queued, or before this chunk.
            val head = chunks.removeFirstOrNull()
            if (head != null) chunks.addFirst(Chunk(head.samples, head.capturedAtMs, discontinuity = true))
            else gapPending = true
        }
        chunks.addLast(Chunk(samples, capturedAtMs, discontinuity = gapPending))
        gapPending = false
        queuedSamples += samples.size
        lock.notifyAll()
        dropped
    }

    /** Marks a gap the capture side created on purpose, e.g. while this app's own TTS played. */
    fun markGap() = synchronized(lock) { gapPending = true }

    /** Blocks up to [timeoutMs]; returns null on timeout, or when closed and drained. */
    fun take(timeoutMs: Long): Chunk? = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (chunks.isEmpty()) {
            if (closed) return null
            val waitMs = (deadline - System.nanoTime()) / 1_000_000
            if (waitMs <= 0) return null
            lock.wait(waitMs)
        }
        val chunk = chunks.removeFirst()
        queuedSamples -= chunk.samples.size
        chunk
    }

    /** Drops everything queued; nothing taken afterwards is joined to the dropped audio. */
    fun clear() = synchronized(lock) {
        if (chunks.isNotEmpty()) gapPending = true
        chunks.clear()
        queuedSamples = 0
    }

    fun close() = synchronized(lock) {
        closed = true
        chunks.clear()
        queuedSamples = 0
        lock.notifyAll()
    }
}

/**
 * Throttles SenseVoice "simulated streaming". Each partial pass re-decodes the whole open
 * utterance, so its cost grows with the utterance and the total work grows quadratically. The
 * interval therefore stretches with the measured cost of the last pass (keeping partial decoding
 * under [maxBusyRatio] of real time), and partials are skipped entirely while the audio queue
 * holds more than [maxBacklogSamples], so finished segments are never delayed by previews.
 */
internal class PartialDecodePolicy(
    private val baseIntervalSamples: Int,
    private val maxBacklogSamples: Int,
    private val maxBusyRatio: Float = 0.3f,
    private val sampleRate: Int = PlaybackAudioCapture.TARGET_RATE,
) {
    private var lastCostMs = 0L

    val intervalSamples: Int
        get() {
            val costBound = (lastCostMs / maxBusyRatio * sampleRate / 1000f).toInt()
            return maxOf(baseIntervalSamples, costBound)
        }

    fun shouldDecode(samplesSincePartial: Int, backlogSamples: Int): Boolean =
        baseIntervalSamples > 0 &&
            samplesSincePartial >= intervalSamples &&
            backlogSamples <= maxBacklogSamples

    fun recordCost(costMs: Long) {
        lastCostMs = costMs.coerceAtLeast(0)
    }

    /** A new utterance starts short and cheap again. */
    fun reset() {
        lastCostMs = 0L
    }
}
