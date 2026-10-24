package com.gameocr.app.ocr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Runs [consume] for every batch in order while [executor] runs [prepare] for the next batch, so
 * cutting crops overlaps recognition of the batch before. Only timing changes: each batch is
 * prepared and consumed exactly as a serial loop would.
 *
 * Every item [prepare] returned ends up in [prepared], also when [consume] or [prepare] throws,
 * so the caller can release them all in one place.
 */
internal fun <T> runPipelinedBatches(
    batchCount: Int,
    executor: ExecutorService,
    prepared: MutableList<T>,
    prepare: (batchIndex: Int) -> List<T>,
    consume: (batchIndex: Int, items: List<T>) -> Unit,
) {
    if (batchCount <= 0) return
    var pending: Future<List<T>>? = executor.submit(Callable { prepare(0) })
    try {
        for (batchIndex in 0 until batchCount) {
            val items = awaitBatch(checkNotNull(pending))
            prepared += items
            pending = if (batchIndex + 1 < batchCount) {
                val next = batchIndex + 1
                executor.submit(Callable { prepare(next) })
            } else {
                null
            }
            consume(batchIndex, items)
        }
    } finally {
        // A batch cut ahead of a failure still owns its crops.
        pending?.let { future -> runCatching { awaitBatch(future) }.getOrNull()?.let { prepared += it } }
    }
}

private fun <T> awaitBatch(future: Future<List<T>>): List<T> = try {
    future.get()
} catch (failure: ExecutionException) {
    throw failure.cause ?: failure
}

/**
 * Recognition width of a [cropWidth] x [cropHeight] crop after the vertical-line rotation that
 * PaddleOcrEngine.prepareRecognitionCrop applies, so batches can be planned before cutting.
 */
internal fun paddlePlannedRecognitionWidth(cropWidth: Int, cropHeight: Int): Int =
    if (paddleVerticalCropRotationDegrees(cropWidth, cropHeight) != null) {
        PaddleRecognitionSizing.plan(cropHeight, cropWidth).targetWidth
    } else {
        PaddleRecognitionSizing.plan(cropWidth, cropHeight).targetWidth
    }
