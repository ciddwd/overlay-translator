package com.gameocr.app.capture

/**
 * Pure scheduling policy for page observation, independent of capture/OCR/translation jobs.
 * The visual comparator must compare against the candidate anchor,
 * not merely the preceding frame, so slow scrolling cannot accumulate unnoticed drift.
 * Null/obscured/failed observations must not be submitted as "unchanged" frames.
 */
internal class SettledPagePolicy {
    data class Decision(
        val revision: Long,
        val invalidatePrevious: Boolean,
        val replaceAnchor: Boolean,
        val ready: Boolean,
    )

    private var revision = 0L
    private var hasAnchor = false
    private var stableSinceMs = 0L
    private var lastObservationMs: Long? = null
    private var submittedRevision: Long? = null
    private var readyRevision: Long? = null
    private var observationPaused = false

    @Synchronized
    fun observe(
        sameAsAnchor: Boolean,
        contextUnchanged: Boolean,
        nowMs: Long,
        stableDurationMs: Long,
    ): Decision {
        val clockWentBack = lastObservationMs?.let { nowMs < it } == true
        lastObservationMs = nowMs
        val changed = !hasAnchor || !contextUnchanged || !sameAsAnchor || clockWentBack
        if (observationPaused) stableSinceMs = nowMs
        observationPaused = false
        if (changed) {
            val invalidate = hasAnchor
            revision++
            hasAnchor = true
            stableSinceMs = nowMs
            submittedRevision = null
            readyRevision = null
            return Decision(revision, invalidate, replaceAnchor = true, ready = false)
        }
        val ready = submittedRevision != revision &&
            nowMs - stableSinceMs >= stableDurationMs.coerceAtLeast(1L)
        readyRevision = revision.takeIf { ready }
        return Decision(
            revision,
            invalidatePrevious = false,
            replaceAnchor = false,
            ready = ready,
        )
    }

    /** Claim only after an execution slot is available; never queue intermediate pages. */
    @Synchronized
    fun claim(expectedRevision: Long): Boolean {
        if (!hasAnchor || expectedRevision != revision || readyRevision != revision || submittedRevision == revision) return false
        submittedRevision = revision
        return true
    }

    @Synchronized
    fun accepts(expectedRevision: Long): Boolean = hasAnchor && expectedRevision == revision

    /** Occlusion is not a page turn. Preserve the displayed/submitted page but restart settling. */
    @Synchronized
    fun pauseObservation(nowMs: Long) {
        observationPaused = true
        stableSinceMs = nowMs
        lastObservationMs = nowMs
        readyRevision = null
    }

    /** A discontinuity (sleep, lost capture session, stopped loop) invalidates pending results. */
    @Synchronized
    fun reset() {
        revision++
        hasAnchor = false
        stableSinceMs = 0L
        lastObservationMs = null
        submittedRevision = null
        readyRevision = null
        observationPaused = false
    }
}
