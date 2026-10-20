package com.gameocr.app.ocr

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaSpeculativeDecodingTest {

    /**
     * A causal fake decoder: the logits at position p depend only on the row and ids[0..p], and
     * positions past a row's length (right padding) are filled with noise that must never be read.
     */
    private class FakeDecoder(private val next: (row: Int, prefix: List<Int>) -> Int) : MangaSpeculativeDecoding.Decoder {
        var calls = 0
        val batchSizes = mutableListOf<Int>()

        override fun logits(rows: IntArray, ids: List<IntArray>, lengths: IntArray): Array<Array<FloatArray>> {
            calls++
            batchSizes += rows.size
            val width = lengths.max()
            return Array(rows.size) { k ->
                Array(width) { position ->
                    if (position < lengths[k]) {
                        oneHot(next(rows[k], ids[k].take(position + 1)), sharpness = 4f + position)
                    } else {
                        oneHot(PAD_NOISE_TOKEN, sharpness = 100f)
                    }
                }
            }
        }
    }

    /** Plain greedy decoding of one row, the result speculative decoding must reproduce. */
    private fun referenceGreedy(row: Int, next: (Int, List<Int>) -> Int): Pair<IntArray, FloatArray> {
        val ids = mutableListOf(START)
        val confidences = FloatArray(MAX_TOKENS + 1) { Float.NaN }
        while (ids.size <= MAX_TOKENS) {
            val prediction = mangaTokenPrediction(arrayOf(oneHot(next(row, ids.toList()), 4f + ids.size - 1)), true)
            if (prediction.tokenId == END) break
            confidences[ids.size] = prediction.confidence
            ids += prediction.tokenId
        }
        return ids.toIntArray() to confidences
    }

    private fun decoding(calculateConfidence: Boolean = true) = MangaSpeculativeDecoding(
        maxTokens = MAX_TOKENS,
        startTokenId = START,
        endTokenId = END,
        calculateConfidence = calculateConfidence,
    )

    @Test
    fun exactDraft_finishesShortRowsInOneVerifyPass() {
        val text = mapOf(0 to listOf(10, 11, 12), 1 to listOf(13), 2 to emptyList(), 3 to listOf(14, 15))
        val next = { row: Int, prefix: List<Int> -> text.getValue(row).getOrElse(prefix.size - 1) { END } }
        val result = decoding().decode(4, FakeDecoder(next), FakeDecoder(next))

        (0 until 4).forEach { row ->
            assertArrayEquals("row $row", (listOf(START) + text.getValue(row)).toIntArray(), result.ids[row])
        }
        assertEquals(1, result.verifyCalls)
        assertEquals(0, result.fallbackRows)
    }

    @Test
    fun wrongDraft_fallsBackAfterTwoRejectionsAndStillMatchesReference() {
        val reference = { _: Int, prefix: List<Int> -> if (prefix.size <= 20) 10 + prefix.size % 5 else END }
        val draft = FakeDecoder { _, _ -> 30 }
        val result = decoding().decode(2, draft, FakeDecoder(reference))

        (0 until 2).forEach { row -> assertArrayEquals(referenceGreedy(row, reference).first, result.ids[row]) }
        assertEquals(2, result.fallbackRows)
        assertEquals(2, result.verifyCalls)
        // The first draft runs 32 steps; the retry is capped at 8.
        assertEquals(32 + 8, result.draftCalls)
    }

    @Test
    fun lengthCap_stopsAtStartPlusMaxTokensLikeProductionGreedy() {
        val reference = { _: Int, prefix: List<Int> -> 10 + prefix.size % 7 }
        val result = decoding().decode(1, FakeDecoder(reference), FakeDecoder(reference))

        assertEquals(MAX_TOKENS + 1, result.ids.single().size)
        assertArrayEquals(referenceGreedy(0, reference).first, result.ids.single())
    }

    @Test
    fun confidences_areTheReferenceConfidencesOfEveryKeptId() {
        val reference = { row: Int, prefix: List<Int> -> if (prefix.size < 6 + row) 10 + (prefix.size + row) % 4 else END }
        // Wrong once at the third id of every row, right otherwise.
        val draft = { row: Int, prefix: List<Int> -> if (prefix.size == 3) 40 else reference(row, prefix) }
        val result = decoding().decode(3, FakeDecoder(draft), FakeDecoder(reference))

        (0 until 3).forEach { row ->
            val (expectedIds, expectedConfidences) = referenceGreedy(row, reference)
            assertArrayEquals(expectedIds, result.ids[row])
            for (index in 1 until expectedIds.size) {
                assertEquals("row $row index $index", expectedConfidences[index], result.confidences[row][index], 0f)
            }
        }
    }

    @Test
    fun withoutConfidence_everyConfidenceIsNaN() {
        val reference = { _: Int, prefix: List<Int> -> if (prefix.size < 5) 10 else END }
        val result = decoding(calculateConfidence = false).decode(1, FakeDecoder(reference), FakeDecoder(reference))

        assertTrue(result.confidences.single().all(Float::isNaN))
    }

    @Test
    fun randomDraftsAndBatches_alwaysReproduceReferenceGreedy() {
        repeat(400) { seed ->
            val random = Random(seed)
            val rowCount = 1 + random.nextInt(4)
            val lengths = IntArray(rowCount) { random.nextInt(MAX_TOKENS + 6) }
            val errorRate = random.nextDouble()
            val reference = { row: Int, prefix: List<Int> ->
                if (prefix.size > lengths[row]) END else 10 + (prefix.sum() * 31 + row * 7 + prefix.size) % 50
            }
            val draft = { row: Int, prefix: List<Int> ->
                val noise = Random(seed * 1_000 + row * 100 + prefix.hashCode())
                if (noise.nextDouble() < errorRate) {
                    if (noise.nextBoolean()) END else 10 + noise.nextInt(50)
                } else {
                    reference(row, prefix)
                }
            }
            val result = decoding().decode(rowCount, FakeDecoder(draft), FakeDecoder(reference))

            (0 until rowCount).forEach { row ->
                val (expectedIds, expectedConfidences) = referenceGreedy(row, reference)
                assertArrayEquals("seed $seed row $row", expectedIds, result.ids[row])
                for (index in 1 until expectedIds.size) {
                    assertEquals("seed $seed row $row index $index", expectedConfidences[index], result.confidences[row][index], 0f)
                }
            }
        }
    }

    @Test
    fun batches_onlyEverContainUndecidedRows() {
        val text = mapOf(0 to List(3) { 10 }, 1 to List(30) { 11 })
        val next = { row: Int, prefix: List<Int> -> text.getValue(row).getOrElse(prefix.size - 1) { END } }
        val draft = FakeDecoder(next)
        val reference = FakeDecoder(next)
        decoding().decode(2, draft, reference)

        // Row 0 leaves the draft batch after its end token; row 1 keeps drafting alone.
        assertEquals(2, draft.batchSizes.first())
        assertEquals(1, draft.batchSizes.last())
        assertEquals(listOf(2), reference.batchSizes)
    }

    private companion object {
        const val START = 2
        const val END = 3
        const val MAX_TOKENS = 48
        const val VOCAB = 64
        const val PAD_NOISE_TOKEN = 63

        fun oneHot(token: Int, sharpness: Float): FloatArray =
            FloatArray(VOCAB) { index -> if (index == token) sharpness else (index % 3) * 0.1f }
    }
}
