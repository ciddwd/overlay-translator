package com.gameocr.app.overlay

internal data class FrameUpdateStats(
    val received: Int,
    val published: Int,
) {
    val coalesced: Int
        get() = (received - published).coerceAtLeast(0)
}

/** Keeps only the latest streaming value until the next UI frame. */
internal class LatestFrameUpdateCoalescer<T>(
    private val scheduleFrame: (Runnable) -> Unit,
    private val publish: (T) -> Unit,
) {
    private var frameScheduled = false
    private var hasPending = false
    private var pending: T? = null
    private var received = 0
    private var published = 0

    fun submit(value: T) {
        received += 1
        pending = value
        hasPending = true
        if (frameScheduled) return
        frameScheduled = true
        scheduleFrame(Runnable {
            frameScheduled = false
            if (!hasPending) return@Runnable
            @Suppress("UNCHECKED_CAST")
            val latest = pending as T
            pending = null
            hasPending = false
            publish(latest)
            published += 1
        })
    }

    /** Prevents a queued partial from overwriting a FINAL/error update. */
    fun discardPending() {
        pending = null
        hasPending = false
    }

    fun stats(): FrameUpdateStats = FrameUpdateStats(received = received, published = published)
}
