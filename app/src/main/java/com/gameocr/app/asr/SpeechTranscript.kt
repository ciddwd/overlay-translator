package com.gameocr.app.asr

internal enum class SpeechRecordState {
    /** Partial text of the utterance still being spoken. */
    LISTENING,
    QUEUED,
    TRANSLATING,
    DONE,
    FAILED,
    /** Its translation was abandoned at a pause or stop; only the source stays. */
    CANCELED,
}

internal data class SpeechRecord(
    /** Stable for the record's lifetime; translations are applied by this id, never by position. */
    val id: Long,
    val generation: Long,
    val segmentId: Long,
    val source: String,
    val translation: String,
    val state: SpeechRecordState,
    val providerTranslated: Boolean = false,
)

/** What the floating window shows for one record, oldest first. */
internal data class SpeechDisplayRecord(val id: Long, val source: String, val translation: String)

/**
 * Display history of recognized speech, in chronological order (older records first). It is
 * separate from translation work: trimming to the display limit never cancels a translation, and
 * a translation for a record that is no longer displayed is simply not shown.
 *
 * Not thread-safe; [SpeechSubtitleCoordinator] serializes access.
 */
internal class SpeechTranscript {
    private val records = ArrayList<SpeechRecord>()
    private var nextId = 1L
    private var providerTrimmedGeneration = -1L
    private var providerTrimmedThrough = -1L

    val all: List<SpeechRecord> get() = records

    /** Full S2TT snapshots update their own row, even when source and translation interleave. */
    fun onProviderResult(generation: Long, event: SpeechEvent) {
        if (event.text.isEmpty() && event.providerTranslation.isNullOrEmpty()) return
        val index = records.indexOfFirst { it.generation == generation && it.segmentId == event.segmentId }
        val current = records.getOrNull(index)
        if (current?.state == SpeechRecordState.CANCELED) return
        if (current == null && generation == providerTrimmedGeneration && event.segmentId <= providerTrimmedThrough) return
        val record = SpeechRecord(
            current?.id ?: nextId++, generation, event.segmentId, event.text,
            event.providerTranslation.orEmpty(),
            when {
                event.providerTranslationFailed -> SpeechRecordState.FAILED
                event.isFinal -> SpeechRecordState.DONE
                else -> SpeechRecordState.TRANSLATING
            }, providerTranslated = true,
        )
        if (index >= 0) records[index] = record else {
            val before = records.indexOfFirst {
                it.providerTranslated && it.generation == generation && it.segmentId > event.segmentId
            }
            if (before >= 0) records.add(before, record) else records.add(record)
        }
    }

    /** Shows or revises the utterance being spoken. */
    fun onPartial(generation: Long, segmentId: Long, text: String) {
        val open = records.lastOrNull()?.takeIf { it.state == SpeechRecordState.LISTENING }
        if (open != null && open.generation == generation && open.segmentId == segmentId) {
            records[records.lastIndex] = open.copy(source = text)
            return
        }
        // Any other open utterance was abandoned by the engine (reset, dropped audio, empty final).
        if (open != null) records.removeAt(records.lastIndex)
        records += SpeechRecord(nextId++, generation, segmentId, text, "", SpeechRecordState.LISTENING)
    }

    /** Removes only the matching unconfirmed row when the server retracts an empty result. */
    fun discardPartial(generation: Long, segmentId: Long) {
        records.removeAll { it.generation == generation && it.segmentId == segmentId && it.state == SpeechRecordState.LISTENING }
    }

    /** Finalizes the matching utterance (or appends a new one) and returns it for translation. */
    fun onFinal(generation: Long, segmentId: Long, text: String): SpeechRecord {
        val open = records.lastOrNull()?.takeIf { it.state == SpeechRecordState.LISTENING }
        if (open != null) records.removeAt(records.lastIndex)
        val id = if (open != null && open.generation == generation && open.segmentId == segmentId) open.id else nextId++
        return SpeechRecord(id, generation, segmentId, text, "", SpeechRecordState.QUEUED).also { records += it }
    }

    /** Applies a (partial or final) translation by id. Returns false when the record is gone. */
    fun applyTranslation(id: Long, translation: String, state: SpeechRecordState): Boolean {
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = records[index]
        // A late result never revives a record that was canceled at a pause.
        if (current.state == SpeechRecordState.CANCELED) return false
        records[index] = current.copy(translation = translation, state = state)
        return true
    }

    /**
     * Pause/resume/stop boundary: drops the unfinished utterance and marks every translation that
     * has not finished as canceled. Finished history stays, but nothing new is joined to it.
     */
    fun closeGeneration() {
        records.removeAll { it.state == SpeechRecordState.LISTENING }
        records.replaceAll { record ->
            when (record.state) {
                SpeechRecordState.QUEUED, SpeechRecordState.TRANSLATING ->
                    record.copy(translation = if (record.providerTranslated) record.translation else "", state = SpeechRecordState.CANCELED)
                else -> record
            }
        }
    }

    /** Keeps the newest [maxRecords]; the open utterance is never trimmed. */
    fun trim(maxRecords: Int) {
        val excess = records.size - maxRecords.coerceAtLeast(1)
        if (excess > 0) {
            records.take(excess).filter { it.providerTranslated }.forEach {
                if (it.generation != providerTrimmedGeneration) {
                    providerTrimmedGeneration = it.generation
                    providerTrimmedThrough = -1L
                }
                providerTrimmedThrough = maxOf(providerTrimmedThrough, it.segmentId)
            }
            records.subList(0, excess).clear()
        }
    }

    fun clear() {
        records.clear()
        providerTrimmedGeneration = -1L
        providerTrimmedThrough = -1L
    }

    fun display(): List<SpeechDisplayRecord> = records.map { record ->
        val translation = when (record.state) {
            SpeechRecordState.LISTENING, SpeechRecordState.QUEUED -> PENDING
            SpeechRecordState.TRANSLATING -> record.translation.ifEmpty { PENDING }
            // A failed or canceled translation leaves the source line on its own.
            SpeechRecordState.DONE, SpeechRecordState.FAILED, SpeechRecordState.CANCELED -> record.translation
        }
        SpeechDisplayRecord(record.id, record.source, translation)
    }

    companion object {
        const val PENDING = "…"
    }
}
