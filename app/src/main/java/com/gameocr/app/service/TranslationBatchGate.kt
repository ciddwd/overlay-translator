package com.gameocr.app.service

import java.util.concurrent.atomic.AtomicLong

/** Allows overlay updates only from the currently active capture batch. */
internal class TranslationBatchGate {
    private val activeBatchId = AtomicLong(NO_ACTIVE_BATCH)

    fun activate(batchId: Long): Long? = activeBatchId
        .getAndSet(batchId)
        .takeUnless { it == NO_ACTIVE_BATCH }

    fun invalidate(): Long? = activeBatchId
        .getAndSet(NO_ACTIVE_BATCH)
        .takeUnless { it == NO_ACTIVE_BATCH }

    fun accepts(batchId: Long?): Boolean =
        batchId != null && activeBatchId.get() == batchId

    private companion object {
        const val NO_ACTIVE_BATCH = Long.MIN_VALUE
    }
}
