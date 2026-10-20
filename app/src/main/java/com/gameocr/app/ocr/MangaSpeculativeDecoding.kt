package com.gameocr.app.ocr

/**
 * Batched speculative greedy decoding (Leviathan et al., ICML 2023) for manga-ocr, independent
 * from ONNX Runtime so its token bookkeeping can be tested with fake decoders.
 *
 * Every round, the rows still undecided draft with the small decoder, then the reference decoder
 * checks all drafts in one batched pass that yields its own choice at every drafted position. A
 * draft is kept up to its first disagreement, where the reference token is taken; a fully accepted
 * draft also gains the reference's next token. Every output id is therefore the reference's own
 * argmax after the reference's own prefix: the result is the reference's greedy output, and the
 * draft decoder only changes how fast it arrives.
 *
 * A rejected draft lowers the next draft's length to [retryDraftTokens]. A row rejected
 * [maxRejectedDrafts] times evidently confuses the draft decoder, so it finishes on the reference
 * alone and costs little more than plain reference decoding.
 */
internal class MangaSpeculativeDecoding(
    private val maxTokens: Int,
    private val startTokenId: Int,
    private val endTokenId: Int,
    private val draftTokens: Int = DEFAULT_DRAFT_TOKENS,
    private val retryDraftTokens: Int = DEFAULT_RETRY_DRAFT_TOKENS,
    private val maxRejectedDrafts: Int = DEFAULT_MAX_REJECTED_DRAFTS,
    private val calculateConfidence: Boolean,
) {
    /**
     * One batched decoder call. Row k holds ids[k][0 until lengths[k]] for encoder row rows[k];
     * the result holds logits for every row and every position up to that row's length.
     */
    fun interface Decoder {
        fun logits(rows: IntArray, ids: List<IntArray>, lengths: IntArray): Array<Array<FloatArray>>
    }

    class Result(
        /** START followed by the decoded ids, without the end token, per row. */
        val ids: List<IntArray>,
        /** The reference's softmax confidence for ids[r][i], at index i; NaN when not calculated. */
        val confidences: List<FloatArray>,
        val draftCalls: Int,
        val verifyCalls: Int,
        val fallbackCalls: Int,
        val fallbackRows: Int,
    )

    private class Draft(val ids: IntArray, val ended: Boolean)

    init {
        require(maxTokens > 0 && draftTokens > 0 && retryDraftTokens > 0 && maxRejectedDrafts > 0)
    }

    fun decode(rowCount: Int, draft: Decoder, reference: Decoder, checkActive: () -> Unit = {}): Result {
        require(rowCount > 0) { "rowCount must be positive" }
        val accepted = MutableList(rowCount) { intArrayOf(startTokenId) }
        val confidences = List(rowCount) { FloatArray(maxTokens + 1) { Float.NaN } }
        val results = arrayOfNulls<IntArray>(rowCount)
        val rejected = IntArray(rowCount)
        var draftCalls = 0
        var verifyCalls = 0
        while (true) {
            val rows = (0 until rowCount).filter { results[it] == null && rejected[it] < maxRejectedDrafts }.toIntArray()
            if (rows.isEmpty()) break
            val drafted = continueGreedy(
                decoder = draft,
                rows = rows,
                prefixes = rows.map { accepted[it] },
                maxNewTokens = IntArray(rows.size) { if (rejected[rows[it]] == 0) draftTokens else retryDraftTokens },
                confidences = null,
                checkActive = checkActive,
            ).also { draftCalls += it.second }.first
            checkActive()
            val logits = reference.logits(rows, drafted.map { it.ids }, IntArray(rows.size) { verifyLength(drafted[it]) })
            verifyCalls++
            rows.forEachIndexed { batchIndex, row ->
                val outcome = judge(accepted[row].size, drafted[batchIndex], logits[batchIndex], confidences[row])
                if (outcome.done) {
                    results[row] = outcome.ids
                } else {
                    accepted[row] = outcome.ids
                    if (outcome.rejected) rejected[row]++
                }
            }
        }
        val fallbackRows = (0 until rowCount).filter { results[it] == null }.toIntArray()
        var fallbackCalls = 0
        if (fallbackRows.isNotEmpty()) {
            val (finished, calls) = continueGreedy(
                decoder = reference,
                rows = fallbackRows,
                prefixes = fallbackRows.map { accepted[it] },
                maxNewTokens = IntArray(fallbackRows.size) { maxTokens },
                confidences = fallbackRows.map { confidences[it] },
                checkActive = checkActive,
            )
            fallbackCalls = calls
            finished.forEachIndexed { batchIndex, rowDraft -> results[fallbackRows[batchIndex]] = rowDraft.ids }
        }
        return Result(
            ids = results.map { checkNotNull(it) },
            confidences = confidences,
            draftCalls = draftCalls,
            verifyCalls = verifyCalls,
            fallbackCalls = fallbackCalls,
            fallbackRows = fallbackRows.size,
        )
    }

    /**
     * Greedy decoding for [rows] from their [prefixes] until the end token, the length cap or each
     * row's [maxNewTokens], one batched call per step, exactly as the production greedy loop: the
     * decoder only ever sees inputs of at most [maxTokens] ids. When [confidences] is given, each
     * appended id's confidence is recorded at its index.
     */
    private fun continueGreedy(
        decoder: Decoder,
        rows: IntArray,
        prefixes: List<IntArray>,
        maxNewTokens: IntArray,
        confidences: List<FloatArray>?,
        checkActive: () -> Unit,
    ): Pair<List<Draft>, Int> {
        val ids = prefixes.map { it.copyOf(maxTokens + 1) }
        val lengths = IntArray(rows.size) { prefixes[it].size }
        val ended = BooleanArray(rows.size)
        var calls = 0
        while (true) {
            val active = rows.indices.filter { index ->
                !ended[index] && lengths[index] <= maxTokens && lengths[index] - prefixes[index].size < maxNewTokens[index]
            }
            if (active.isEmpty()) break
            checkActive()
            val logits = decoder.logits(
                IntArray(active.size) { rows[active[it]] },
                active.map { ids[it] },
                IntArray(active.size) { lengths[active[it]] },
            )
            calls++
            active.forEachIndexed { batchIndex, index ->
                val prediction = predict(logits[batchIndex], lengths[index] - 1, confidences != null)
                if (prediction.tokenId == endTokenId) {
                    ended[index] = true
                } else {
                    confidences?.get(index)?.set(lengths[index], prediction.confidence)
                    ids[index][lengths[index]++] = prediction.tokenId
                }
            }
        }
        return rows.indices.map { Draft(ids[it].copyOf(lengths[it]), ended[it]) } to calls
    }

    /**
     * Greedy decoding stops once START plus [maxTokens] ids exist, without another decoder call,
     * so a draft at the cap needs no reference choice after its last id.
     */
    private fun verifyLength(draft: Draft): Int =
        if (draft.ids.size > maxTokens) draft.ids.size - 1 else draft.ids.size

    private class Outcome(val ids: IntArray, val done: Boolean, val rejected: Boolean)

    /**
     * Checks [draft], which extends an accepted prefix of [acceptedSize] ids, against the
     * reference [logits], recording the reference's confidence for every id it keeps.
     */
    private fun judge(acceptedSize: Int, draft: Draft, logits: Array<FloatArray>, confidences: FloatArray): Outcome {
        val verifyLength = verifyLength(draft)
        var position = acceptedSize - 1
        var prediction: MangaTokenPrediction? = null
        var rejected = false
        while (position < verifyLength) {
            val current = predict(logits, position, calculateConfidence)
            prediction = current
            val expected = when {
                position + 1 < draft.ids.size -> draft.ids[position + 1]
                draft.ended -> endTokenId
                else -> break // past a draft cut by its length limit: predicted is a free extra id
            }
            if (current.tokenId != expected) {
                rejected = true
                break
            }
            if (current.tokenId != endTokenId) confidences[position + 1] = current.confidence
            position++
        }
        if (position == verifyLength && (draft.ids.size > maxTokens || draft.ended)) {
            return Outcome(draft.ids, done = true, rejected = false)
        }
        // Reached only after a rejection or a length-limited draft, so a prediction exists.
        val taken = checkNotNull(prediction)
        val kept = draft.ids.copyOf(position + 1)
        if (taken.tokenId == endTokenId) return Outcome(kept, done = true, rejected = rejected)
        confidences[position + 1] = taken.confidence
        val accepted = kept + taken.tokenId
        return Outcome(accepted, done = accepted.size > maxTokens, rejected = rejected)
    }

    private fun predict(logits: Array<FloatArray>, position: Int, withConfidence: Boolean): MangaTokenPrediction =
        mangaTokenPrediction(arrayOf(logits[position]), withConfidence && calculateConfidence)

    companion object {
        /** Nearly every logged dialogue line fits one draft; 16 cost long clean lines extra passes. */
        const val DEFAULT_DRAFT_TOKENS = 32
        /** After a rejection the draft decoder may be lost on this crop, so the next try is short. */
        const val DEFAULT_RETRY_DRAFT_TOKENS = 8
        const val DEFAULT_MAX_REJECTED_DRAFTS = 2
    }
}
