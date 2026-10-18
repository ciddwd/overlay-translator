package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingWindowContentMode

/**
 * Reading-position rules for speech records in the floating window. New records are appended at
 * the bottom; the window follows them while the reader is at the bottom, and otherwise keeps the
 * record they are reading at the same height, even when records above it gain a translation.
 */
internal object SpeechScrollPolicy {
    fun isAtBottom(scrollY: Int, viewportHeight: Int, contentHeight: Int, slopPx: Int): Boolean =
        contentHeight <= viewportHeight || scrollY + viewportHeight >= contentHeight - slopPx

    /** Index of the record containing [charOffset]: the last record starting at or before it. */
    fun recordIndexAt(starts: IntArray, charOffset: Int): Int {
        var index = 0
        for (i in starts.indices) {
            if (starts[i] <= charOffset) index = i else break
        }
        return index
    }

    /**
     * Where the anchored record is now. A record trimmed off the top resolves to the oldest
     * remaining record with no offset, so the reader stays near the top of what is left.
     */
    fun resolveAnchor(ids: List<Long>, anchorId: Long, anchorOffsetPx: Int): Pair<Int, Int>? {
        if (ids.isEmpty()) return null
        val index = ids.indexOf(anchorId)
        return if (index >= 0) index to anchorOffsetPx else 0 to 0
    }
}

/** Character offset where each pair starts in [styledFloatingWindowText]. */
internal fun floatingWindowPairStartOffsets(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
): IntArray {
    val starts = IntArray(pairs.size) { -1 }
    var offset = 0
    floatingWindowTextSegments(pairs, mode).forEach { segment ->
        if (starts[segment.pairIndex] < 0) starts[segment.pairIndex] = offset
        offset += segment.text.length
    }
    return starts
}
