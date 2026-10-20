package com.gameocr.app.ocr

import java.nio.LongBuffer
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MangaOcrBatchPolicyTest {

    @Test
    fun greedyState_tableDriven_matchesIndependentGreedyPrefixes() {
        data class Case(
            val name: String,
            val rowCount: Int,
            val maxTokens: Int,
            val decoderSteps: List<IntArray>,
            val expectedTokens: List<List<Int>>,
            val expectedActiveRows: List<List<Int>>,
            val expectedInputs: List<List<Int>>,
        )

        val cases = listOf(
            Case(
                name = "single row ends normally",
                rowCount = 1,
                maxTokens = 4,
                decoderSteps = listOf(intArrayOf(10), intArrayOf(11), intArrayOf(EOS)),
                expectedTokens = listOf(listOf(10, 11)),
                expectedActiveRows = listOf(listOf(0), listOf(0), listOf(0)),
                expectedInputs = listOf(
                    listOf(START),
                    listOf(START, 10),
                    listOf(START, 10, 11),
                ),
            ),
            Case(
                name = "finished row leaves batch while peer continues",
                rowCount = 2,
                maxTokens = 5,
                decoderSteps = listOf(
                    intArrayOf(EOS, 20),
                    intArrayOf(21),
                    intArrayOf(EOS),
                ),
                expectedTokens = listOf(emptyList(), listOf(20, 21)),
                expectedActiveRows = listOf(listOf(0, 1), listOf(1), listOf(1)),
                expectedInputs = listOf(
                    listOf(START, START),
                    listOf(START, 20),
                    listOf(START, 20, 21),
                ),
            ),
            Case(
                name = "all rows stop at token limit",
                rowCount = 2,
                maxTokens = 2,
                decoderSteps = listOf(intArrayOf(30, 40), intArrayOf(31, 41)),
                expectedTokens = listOf(listOf(30, 31), listOf(40, 41)),
                expectedActiveRows = listOf(listOf(0, 1), listOf(0, 1)),
                expectedInputs = listOf(
                    listOf(START, START),
                    listOf(START, 30, START, 40),
                ),
            ),
        )

        for (case in cases) {
            val state = MangaGreedyBatchState(
                rowCount = case.rowCount,
                maxTokens = case.maxTokens,
                startTokenId = START,
                endTokenId = EOS,
            )
            val actualInputs = mutableListOf<List<Int>>()
            val actualActiveRows = mutableListOf<List<Int>>()
            for (step in case.decoderSteps) {
                val activeRows = state.activeRows()
                actualActiveRows += activeRows.toList()
                val input = LongBuffer.allocate(activeRows.size * state.inputLength)
                state.writeInput(input, activeRows)
                actualInputs += input.array().map(Long::toInt)
                state.accept(activeRows, step)
            }
            val actualTokens = (0 until case.rowCount).map { row ->
                state.tokenIds(row)
                    .slice(1 until state.tokenLength(row))
            }
            assertEquals(case.name, case.expectedActiveRows, actualActiveRows)
            assertEquals(case.name, case.expectedInputs, actualInputs)
            assertEquals(case.name, case.expectedTokens, actualTokens)
            assertEquals(case.name, true, state.isComplete)
        }
    }

    @Test
    fun batchSizes_tableDriven_acceptsOnlyBenchmarkCandidates() {
        assertEquals(4, MangaOcrBatchPolicy.DEFAULT_BATCH_SIZE)
        for (candidate in MangaOcrBatchPolicy.benchmarkCandidates) {
            assertEquals(candidate, MangaOcrBatchPolicy.effectiveBatchSize(candidate))
        }
        for (invalid in listOf(0, 3, 5, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                MangaOcrBatchPolicy.effectiveBatchSize(invalid)
            }
        }
    }

    @Test
    fun hiddenRowCompactor_tableDriven_preservesOriginalRowMapping() {
        data class Case(
            val name: String,
            val selectedRows: IntArray,
            val expected: List<Float>,
        )
        val cases = listOf(
            Case("middle rows", intArrayOf(1, 2), listOf(10f, 11f, 12f, 20f, 21f, 22f)),
            Case("non-contiguous rows", intArrayOf(0, 3), listOf(0f, 1f, 2f, 30f, 31f, 32f)),
            Case("single surviving row", intArrayOf(2), listOf(20f, 21f, 22f)),
        )
        val source = FloatBuffer.wrap(
            floatArrayOf(
                0f, 1f, 2f,
                10f, 11f, 12f,
                20f, 21f, 22f,
                30f, 31f, 32f,
            )
        )

        for (case in cases) {
            val destination = FloatBuffer.allocate(case.selectedRows.size * 3)
            MangaHiddenRowCompactor.copyRows(
                source = source,
                sourceRowCount = 4,
                elementsPerRow = 3,
                selectedRows = case.selectedRows,
                destination = destination,
            )
            assertEquals(case.name, case.expected, destination.array().toList())
        }
    }

    @Test
    fun hiddenRowCompactor_tableDriven_rejectsInvalidSelections() {
        val invalidRows = listOf(
            intArrayOf(),
            intArrayOf(-1),
            intArrayOf(4),
            intArrayOf(1, 1),
        )
        for (rows in invalidRows) {
            assertThrows(IllegalArgumentException::class.java) {
                MangaHiddenRowCompactor.copyRows(
                    source = FloatBuffer.wrap(FloatArray(12)),
                    sourceRowCount = 4,
                    elementsPerRow = 3,
                    selectedRows = rows,
                    destination = FloatBuffer.allocate(rows.size * 3),
                )
            }
        }
    }

    @Test
    fun hiddenBulkCopy_preservesRawBitsOffsetsAndBufferState_tableDriven() {
        val bits = intArrayOf(0, 0x80000000.toInt(), 0x7f800000, 0xff800000.toInt(),
            0x7fc01234, 1, 0x3f800000, 0xbf000000.toInt())
        val values = FloatArray(32) { Float.fromBits(bits[it % bits.size]) }
        fun direct(order: ByteOrder): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(order).asFloatBuffer().apply { put(values); clear() }
        val sources = listOf(
            FloatBuffer.wrap(values.copyOf()),
            FloatBuffer.wrap(values.copyOf()).apply { position(2) }.slice(),
            direct(ByteOrder.nativeOrder()), direct(ByteOrder.BIG_ENDIAN),
            direct(ByteOrder.nativeOrder()).asReadOnlyBuffer(),
            ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(values); clear() },
        )
        for (source in sources) for (rows in listOf(intArrayOf(0, 3), intArrayOf(3, 1), intArrayOf(2))) {
            source.position(3)
            source.limit(23)
            val expected = rows.flatMap { row -> (0 until 5).map { source.get(3 + row * 5 + it).toRawBits() } }
            for (directDestination in listOf(false, true)) {
                val capacity = expected.size + 4
                val destination = if (directDestination) ByteBuffer.allocateDirect(capacity * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer() else FloatBuffer.allocate(capacity)
                for (i in 0 until capacity) destination.put(i, 42f)
                destination.position(2)
                destination.limit(capacity - 1)
                MangaHiddenRowCompactor.copyRows(source, 4, 5, rows, destination)
                assertEquals(expected, expected.indices.map { destination.get(it + 2).toRawBits() })
                assertEquals(3, source.position())
                assertEquals(23, source.limit())
                assertEquals(expected.size + 2, destination.position())
                assertEquals(42f.toRawBits(), destination.get(0).toRawBits())
                assertEquals(42f.toRawBits(), destination.get(expected.size + 2).toRawBits())
            }
        }
    }

    private companion object {
        const val START = 2
        const val EOS = 3
    }
}
