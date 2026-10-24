package com.gameocr.app.overlay

import com.gameocr.app.data.PresetGesture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

data class GesturePoint(val x: Float, val y: Float)

/** Fixed templates: translation/size independent, but preserves direction and corner order. */
object PresetGestureRecognizer {
    private const val SAMPLE_COUNT = 48

    private fun distance(a: GesturePoint, b: GesturePoint) = hypot(a.x - b.x, a.y - b.y)

    fun recognize(points: List<GesturePoint>, minimumSize: Float): PresetGesture? {
        if (points.size < 2 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        if (max(width, height) < minimumSize) return null
        val sampled = normalize(points) ?: return null
        val ranked = templates.map { (gesture, variants) ->
            gesture to variants.minOf { template ->
                sampled.indices.sumOf { distance(sampled[it], template[it]).toDouble() } / SAMPLE_COUNT
            }
        }.sortedBy { it.second }
        val best = ranked.first()
        // Reject poor/ambiguous drawings instead of always firing the closest action.
        if (best.second > 0.12 || ranked[1].second - best.second < 0.035) return null
        return best.first
    }

    private fun normalize(points: List<GesturePoint>): List<GesturePoint>? {
        val lengths = FloatArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + distance(points[i - 1], points[i])
        val total = lengths.last()
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val width = points.maxOf { it.x } - left
        val height = points.maxOf { it.y } - top
        val scale = max(width, height)
        if (total <= 0f || scale <= 0f) return null
        var segment = 1
        return List(SAMPLE_COUNT) { i ->
            val position = total * i / (SAMPLE_COUNT - 1)
            while (segment < points.lastIndex && lengths[segment] < position) segment++
            val a = points[segment - 1]
            val b = points[segment]
            val length = lengths[segment] - lengths[segment - 1]
            val fraction = if (length == 0f) 0f else (position - lengths[segment - 1]) / length
            GesturePoint(
                (a.x + (b.x - a.x) * fraction - left - width / 2) / scale,
                (a.y + (b.y - a.y) * fraction - top - height / 2) / scale,
            )
        }
    }

    private fun path(vararg xy: Float): List<GesturePoint> =
        xy.toList().chunked(2).map { GesturePoint(it[0], it[1]) }

    private val templates: Map<PresetGesture, List<List<GesturePoint>>> by lazy {
        val raw = linkedMapOf(
            PresetGesture.UP to mutableListOf(path(0f, 1f, 0f, 0f)),
            PresetGesture.DOWN to mutableListOf(path(0f, 0f, 0f, 1f)),
            PresetGesture.LEFT to mutableListOf(path(1f, 0f, 0f, 0f)),
            PresetGesture.RIGHT to mutableListOf(path(0f, 0f, 1f, 0f)),
            PresetGesture.RIGHT_DOWN to mutableListOf<List<GesturePoint>>(),
            PresetGesture.DOWN_RIGHT to mutableListOf<List<GesturePoint>>(),
            PresetGesture.S to mutableListOf<List<GesturePoint>>(),
            PresetGesture.O to mutableListOf<List<GesturePoint>>(),
        )
        for (ratio in listOf(0.5f, 0.75f, 1f, 1.5f, 2f)) {
            raw.getValue(PresetGesture.RIGHT_DOWN).add(path(0f, 0f, ratio, 0f, ratio, 1f))
            raw.getValue(PresetGesture.DOWN_RIGHT).add(path(0f, 0f, 0f, 1f, ratio, 1f))
        }
        val s = path(
            0.9f, 0.12f, 0.7f, 0f, 0.35f, 0f, 0.1f, 0.12f, 0f, 0.28f,
            0.12f, 0.42f, 0.4f, 0.5f, 0.7f, 0.58f, 0.9f, 0.72f,
            0.85f, 0.88f, 0.6f, 1f, 0.25f, 1f, 0.05f, 0.88f,
        )
        for (ratio in listOf(0.6f, 0.8f, 1f)) {
            val variant = s.map { GesturePoint(it.x * ratio, it.y) }
            raw.getValue(PresetGesture.S).add(variant)
            raw.getValue(PresetGesture.S).add(variant.reversed())
        }
        // O accepts either drawing direction and any starting point, including oval shapes.
        for ((rx, ry) in listOf(1f to 1f, 0.65f to 1f, 1f to 0.65f)) {
            for (start in 0 until 16) for (direction in listOf(-1, 1)) {
                raw.getValue(PresetGesture.O).add(List(65) { i ->
                    val angle = start * PI / 8 + direction * i * PI / 32
                    GesturePoint((rx * cos(angle)).toFloat(), (ry * sin(angle)).toFloat())
                })
            }
        }
        raw.mapValues { (_, variants) -> variants.map { checkNotNull(normalize(it)) } }
    }
}
