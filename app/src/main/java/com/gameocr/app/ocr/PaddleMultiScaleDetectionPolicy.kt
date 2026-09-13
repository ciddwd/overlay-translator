package com.gameocr.app.ocr

import com.gameocr.app.data.PaddleModelVersion
import kotlin.math.hypot

/** Geometry-only reconciliation. Recognition text must never influence detection selection. */
internal object PaddleMultiScaleDetectionPolicy {
    data class Selection(
        val quads: List<DBPostprocessor.Quad>,
        val recovered: List<DBPostprocessor.Quad>,
        val replacedCount: Int,
    )

    fun coarseLimit(version: PaddleModelVersion?, normalPlan: PaddleDetectionResizePlan): Int? {
        // Only this recognizer's real-image cases have been validated. Manga's shared detector
        // and other model versions retain their existing single-scale behavior.
        if (version != PaddleModelVersion.V5_KOREAN) return null
        val normalSide = maxOf(normalPlan.targetWidth, normalPlan.targetHeight)
        val half = (normalSide / 2 / 32) * 32
        return half.takeIf { it >= 64 && minOf(normalPlan.targetWidth, normalPlan.targetHeight) >= 64 }
    }

    fun select(normal: List<DBPostprocessor.Quad>, coarse: List<DBPostprocessor.Quad>): Selection {
        if (normal.isEmpty() || coarse.isEmpty()) return Selection(normal, emptyList(), 0)
        val removed = mutableSetOf<Int>()
        val recovered = mutableListOf<DBPostprocessor.Quad>()
        // Smallest supported parent wins; never grow a region by transitively merging parents.
        for (candidate in coarse.filter(::valid).sortedWith(compareBy({ it.width * it.height }, { it.centerY }, { it.centerX }))) {
            val frame = Frame(candidate)
            val parent = frame.bounds(candidate)
            if (recovered.any { overlapFraction(candidate, it) > 0.1f }) continue
            val members = mutableListOf<Pair<Int, Bounds>>()
            var conflict = false
            normal.forEachIndexed { index, child ->
                if (!valid(child)) return@forEachIndexed
                val bounds = frame.bounds(child)
                val support = frame.bounds(child.support?.takeIf(::valid) ?: child)
                val overlap = parent.intersection(support) / support.area
                if (overlap <= 0.1f) return@forEachIndexed
                // Test actual detector support, not unclip padding. Padding may extend outside
                // a complete coarse crop without any text being cut. Real partial text remains
                // a conflict; never relax containment just because the recognizer likes a word.
                if (overlap < 0.9f || index in removed) {
                    conflict = true
                } else {
                    members += index to bounds
                }
            }
            // A single enlarged detection is not evidence of fragmented text (icons and
            // decorations also grow at coarse scales). Require multiple supported fragments.
            if (conflict || members.size < 2) continue
            val boxes = members.map { it.second }
            val maxHeight = boxes.maxOf { it.height }
            // Similar boxes are ordinary duplicate detections, not missing-character recovery.
            if (parent.height < maxHeight * 1.6f || parent.height > maxHeight * 4f) continue
            // Separate full-width rows must not collapse. Narrow upper/lower stroke fragments
            // may sit at different heights inside ONE large glyph, so center spread alone is
            // insufficient. Test horizontal coverage of each vertical band in the parent frame.
            if (hasMultipleTextRows(boxes, parent.width)) continue
            val coveredWidth = boxes.maxOf { it.right } - boxes.minOf { it.left }
            if (parent.width > coveredWidth * 2f) continue
            // A candidate may complete strokes across a line, but must not bridge large gaps.
            val ordered = boxes.sortedBy { it.left }
            if (ordered.zipWithNext().any { (a, b) -> b.left - a.right > maxHeight * 1.5f }) continue
            removed += members.map { it.first }
            recovered += candidate
        }
        val selected = normal.filterIndexed { index, _ -> index !in removed } + recovered
        return Selection(selected, recovered, removed.size)
    }

    private fun valid(q: DBPostprocessor.Quad): Boolean =
        listOf(q.p0, q.p1, q.p2, q.p3).all { it.x.isFinite() && it.y.isFinite() } &&
            q.width > 0f && q.height > 0f

    private fun hasMultipleTextRows(boxes: List<Bounds>, parentWidth: Float): Boolean {
        val bands = mutableListOf<MutableList<Bounds>>()
        for (box in boxes.sortedBy { it.centerY }) {
            val band = bands.lastOrNull()?.takeIf { members ->
                members.all { kotlin.math.abs(it.centerY - box.centerY) <= minOf(it.height, box.height) * 0.65f }
            }
            if (band == null) bands += mutableListOf(box) else band += box
        }
        return bands.count { band ->
            // Count union coverage, not a bounding span across empty inter-column gaps.
            var end = Float.NEGATIVE_INFINITY
            var coverage = 0f
            for (box in band.sortedBy { it.left }) {
                coverage += (box.right - maxOf(box.left, end)).coerceAtLeast(0f)
                end = maxOf(end, box.right)
            }
            coverage >= parentWidth * 0.5f
        } > 1
    }

    private fun overlapFraction(a: DBPostprocessor.Quad, b: DBPostprocessor.Quad): Float {
        val frame = Frame(a)
        val ar = frame.bounds(a)
        val br = frame.bounds(b)
        return ar.intersection(br) / minOf(ar.area, br.area).coerceAtLeast(1e-6f)
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2f
        val area get() = (width * height).coerceAtLeast(1e-6f)
        fun intersection(other: Bounds): Float =
            (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f) *
                (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    }

    private class Frame(q: DBPostprocessor.Quad) {
        private val origin = q.p0
        private val end = if (q.width >= q.height) q.p1 else q.p3
        private val length = hypot(end.x - origin.x, end.y - origin.y)
        private val ux = (end.x - origin.x) / length
        private val uy = (end.y - origin.y) / length
        fun bounds(q: DBPostprocessor.Quad): Bounds {
            val points = listOf(q.p0, q.p1, q.p2, q.p3)
            val x = points.map { (it.x - origin.x) * ux + (it.y - origin.y) * uy }
            val y = points.map { -(it.x - origin.x) * uy + (it.y - origin.y) * ux }
            return Bounds(x.min(), y.min(), x.max(), y.max())
        }
    }
}
