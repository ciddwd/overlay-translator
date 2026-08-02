package com.gameocr.app.ocr

import java.nio.LongBuffer
import java.nio.FloatBuffer

/**
 * Keeps batch sizing and greedy-decoder bookkeeping independent from ONNX Runtime.
 * Finished rows leave the active batch immediately. Original row indices are retained so
 * every decoded prefix still maps back to the crop that produced it.
 */
internal object MangaOcrBatchPolicy {
    val benchmarkCandidates: List<Int> = listOf(1, 2, 4)
    const val DEFAULT_BATCH_SIZE: Int = 4

    fun effectiveBatchSize(requested: Int): Int {
        require(requested in benchmarkCandidates) {
            "Unsupported Manga OCR batch size: $requested"
        }
        return requested
    }
}

internal object MangaHiddenRowCompactor {
    fun copyRows(
        source: FloatBuffer,
        sourceRowCount: Int,
        elementsPerRow: Int,
        selectedRows: IntArray,
        destination: FloatBuffer,
    ) {
        require(sourceRowCount > 0)
        require(elementsPerRow > 0)
        require(sourceRowCount <= Int.MAX_VALUE / elementsPerRow)
        require(selectedRows.size <= Int.MAX_VALUE / elementsPerRow)
        require(selectedRows.isNotEmpty())
        require(selectedRows.distinct().size == selectedRows.size)
        require(selectedRows.all { row -> row in 0 until sourceRowCount })
        require(source.remaining() >= sourceRowCount * elementsPerRow)
        require(destination.remaining() >= selectedRows.size * elementsPerRow)

        val sourceStart = source.position()
        for (row in selectedRows) {
            val rowValues = source.duplicate()
            val rowStart = sourceStart + row * elementsPerRow
            rowValues.position(rowStart)
            rowValues.limit(rowStart + elementsPerRow)
            destination.put(rowValues)
        }
    }
}

internal class MangaGreedyBatchState(
    rowCount: Int,
    private val maxTokens: Int,
    private val startTokenId: Int,
    private val endTokenId: Int,
) {
    private val tokenIds: Array<IntArray>
    private val tokenLengths: IntArray
    private val finished: BooleanArray

    var inputLength: Int = 1
        private set

    val rowCount: Int = rowCount
    val activeRowCount: Int
        get() = finished.count { !it }
    val isComplete: Boolean
        get() = activeRowCount == 0

    init {
        require(rowCount > 0) { "rowCount must be positive" }
        require(maxTokens > 0) { "maxTokens must be positive" }
        tokenIds = Array(rowCount) { IntArray(maxTokens + 1).apply { this[0] = startTokenId } }
        tokenLengths = IntArray(rowCount) { 1 }
        finished = BooleanArray(rowCount)
    }

    fun activeRows(): IntArray = IntArray(activeRowCount).also { rows ->
        var outputIndex = 0
        for (row in 0 until rowCount) {
            if (!finished[row]) rows[outputIndex++] = row
        }
    }

    fun writeInput(buffer: LongBuffer, activeRows: IntArray) {
        requireActiveSnapshot(activeRows)
        for (row in activeRows) {
            for (index in 0 until inputLength) {
                buffer.put(tokenIds[row][index].toLong())
            }
        }
    }

    fun accept(activeRows: IntArray, nextTokenIds: IntArray) {
        requireActiveSnapshot(activeRows)
        require(nextTokenIds.size == activeRows.size) {
            "Expected ${activeRows.size} decoder outputs, got ${nextTokenIds.size}"
        }
        check(!isComplete) { "Cannot advance a completed Manga OCR batch" }
        check(inputLength <= maxTokens) { "Manga OCR batch exceeded maxTokens=$maxTokens" }

        for ((activeIndex, row) in activeRows.withIndex()) {
            val nextId = nextTokenIds[activeIndex]
            if (nextId == endTokenId) {
                finished[row] = true
            } else {
                tokenIds[row][inputLength] = nextId
                tokenLengths[row]++
            }
        }
        inputLength++
        if (inputLength > maxTokens) {
            finished.fill(true)
        }
    }

    fun tokenIds(row: Int): IntArray = tokenIds[row]

    fun tokenLength(row: Int): Int = tokenLengths[row]

    private fun requireActiveSnapshot(rows: IntArray) {
        val currentRows = activeRows()
        require(rows.contentEquals(currentRows)) {
            "Stale Manga OCR active rows: expected=${currentRows.contentToString()} " +
                "actual=${rows.contentToString()}"
        }
    }
}
