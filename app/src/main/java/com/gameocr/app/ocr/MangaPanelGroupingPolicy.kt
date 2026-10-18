package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** Keep existing OCR members, but never send text on opposite sides of a frame to one crop. */
internal object MangaPanelGroupingPolicy {
    data class Separator(val vertical: Boolean, val lane: Int, val start: Int, val end: Int)
    data class Result(
        val entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        val originalIndices: List<Int>,
        val splitOriginalIndices: Set<Int>,
        val memberBounds: List<IntRect>,
    )

    fun separators(width: Int, height: Int, argb: IntArray): List<Separator> {
        require(width > 0 && height > 0 && argb.size == width * height)
        val minimumRun = maxOf(24, minOf(width, height) / 10)
        val edgeRadius = maxOf(2, minOf(width, height) / 150)
        val output = mutableListOf<Separator>()
        val luminances = ByteArray(argb.size) { i -> (((argb[i] ushr 16 and 255) * 299 +
            (argb[i] ushr 8 and 255) * 587 + (argb[i] and 255) * 114) / 1000).toByte() }
        fun luma(i: Int): Int = luminances[i].toInt() and 255
        for (vertical in listOf(true, false)) {
            val lanes = if (vertical) width else height
            val length = if (vertical) height else width
            fun index(lane: Int, position: Int) = if (vertical) position * width + lane else lane * width + position
            for (lane in 1 until lanes - 1) {
                // Collapse adjacent dark lanes to the frame's leading edge.
                var position = 0
                while (position < length) {
                    fun dark(position: Int) = (-1..1).any { offset -> luma(index(lane + offset, position)) < 110 }
                    if (!dark(position)) { position++; continue }
                    val start = position
                    var lightBefore = 0; var lightAfter = 0
                    while (position < length && dark(position)) {
                        if ((1..edgeRadius).any { lane-it >= 0 && luma(index(lane-it, position)) >= 225 }) lightBefore++
                        if ((1..edgeRadius).any { lane+it < lanes && luma(index(lane+it, position)) >= 225 }) lightAfter++
                        position++
                    }
                    val run = position - start
                    if (run >= minimumRun && maxOf(lightBefore, lightAfter) >= run * 0.80f) {
                        output += Separator(vertical, lane, start, position)
                    }
                }
            }
        }
        val merged = mutableListOf<Separator>()
        output.sortedWith(compareBy({ it.vertical }, { it.lane }, { it.start })).forEach { line ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.vertical == line.vertical &&
                line.lane - previous.lane <= edgeRadius && line.start <= previous.end && line.end >= previous.start) {
                merged[merged.lastIndex] = previous.copy(start = minOf(previous.start, line.start), end = maxOf(previous.end, line.end))
            } else merged += line
        }
        return merged
    }

    fun split(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        members: List<IntRect>,
        separators: List<Separator>,
    ): Result {
        val output = mutableListOf<MangaOcrBubbleGroupingPolicy.Entry>()
        val origins = mutableListOf<Int>()
        val split = linkedSetOf<Int>()
        val clippedMembers = members.toMutableList()
        entries.forEachIndexed { entryIndex, entry ->
            val indices = entry.bubble.memberIndices
            val parents = IntArray(indices.size) { it }
            fun root(value: Int): Int {
                var current = value
                while (parents[current] != current) { parents[current] = parents[parents[current]]; current = parents[current] }
                return current
            }
            for (a in indices.indices) for (b in a + 1 until indices.size) {
                if (separators.none { separates(it, members[indices[a]], members[indices[b]]) }) {
                    parents[root(b)] = root(a)
                }
            }
            val groups = indices.indices.groupBy(::root).values
            if (groups.size <= 1) {
                output += entry; origins += entryIndex
            } else {
                split += entryIndex
                groups.forEach { group ->
                    val kept = group.map(indices::get)
                    val boxes = kept.map(members::get)
                    var bounds = IntRect(boxes.minOf { it.left }, boxes.minOf { it.top },
                        boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
                    separators.filter { line -> indices.any { a -> indices.any { b ->
                        separates(line, members[a], members[b])
                    } } }.forEach { line ->
                        val centers = boxes.map { if (line.vertical) (it.left + it.right) / 2 else (it.top + it.bottom) / 2 }
                        bounds = when {
                            line.vertical && centers.all { it < line.lane } -> bounds.copy(right = minOf(bounds.right, line.lane))
                            line.vertical && centers.all { it > line.lane } -> bounds.copy(left = maxOf(bounds.left, line.lane + 1))
                            !line.vertical && centers.all { it < line.lane } -> bounds.copy(bottom = minOf(bounds.bottom, line.lane))
                            !line.vertical && centers.all { it > line.lane } -> bounds.copy(top = maxOf(bounds.top, line.lane + 1))
                            else -> bounds
                        }
                    }
                    kept.forEach { member ->
                        val box = members[member]
                        clippedMembers[member] = IntRect(maxOf(box.left, bounds.left), maxOf(box.top, bounds.top),
                            minOf(box.right, bounds.right), minOf(box.bottom, bounds.bottom))
                    }
                    // The recognition padding/evidence rectangle may itself cross the frame.
                    output += entry.copy(bubble = entry.bubble.copy(rect = bounds,
                        contentRect = bounds, memberIndices = kept))
                    origins += entryIndex
                }
            }
        }
        return Result(output, origins, split, clippedMembers)
    }

    private fun separates(line: Separator, first: IntRect, second: IntRect): Boolean {
        val a = if (line.vertical) (first.left + first.right) / 2 else (first.top + first.bottom) / 2
        val b = if (line.vertical) (second.left + second.right) / 2 else (second.top + second.bottom) / 2
        if (line.lane !in minOf(a, b) + 1 until maxOf(a, b)) return false
        // Ruby and short words need not overlap along the frame. Using only their overlap
        // allows them to bridge two panels transitively through the grouping graph.
        val firstAlong = if (line.vertical) (first.top + first.bottom) / 2 else (first.left + first.right) / 2
        val secondAlong = if (line.vertical) (second.top + second.bottom) / 2 else (second.left + second.right) / 2
        val start = minOf(firstAlong, secondAlong)
        val end = maxOf(firstAlong, secondAlong) + 1
        val thickness = if (line.vertical) minOf(first.width, second.width) else minOf(first.height, second.height)
        val overlap = minOf(end, line.end) - maxOf(start, line.start)
        return overlap >= (end - start) * 0.80f && line.end - line.start >= thickness * 3
    }
}
