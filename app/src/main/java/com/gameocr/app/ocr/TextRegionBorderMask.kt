package com.gameocr.app.ocr

import kotlin.math.abs

/** Separates long panel edges from glyphs touching them, without filling the text rectangle. */
internal object TextRegionBorderMask {
    fun detect(width: Int, height: Int, candidate: BooleanArray, semantic: BooleanArray): BooleanArray {
        require(width > 0 && height > 0)
        require(candidate.size == width * height && semantic.size == candidate.size)
        var left = width; var right = -1; var top = height; var bottom = -1
        for (i in semantic.indices) if (semantic[i]) {
            left = minOf(left, i % width); right = maxOf(right, i % width)
            top = minOf(top, i / width); bottom = maxOf(bottom, i / width)
        }
        val lines = BooleanArray(candidate.size)
        if (right < left) return lines
        val minorAxis = minOf(right - left + 1, bottom - top + 1)
        val minimumRun = maxOf(24, (minorAxis * 0.4f).toInt())
        val sideBand = maxOf(2, (minorAxis * 0.18f).toInt())
        val maximumThickness = maxOf(3, (minorAxis * 0.06f).toInt())

        fun scan(vertical: Boolean) {
            val lanes = if (vertical) width else height
            val length = if (vertical) height else width
            val low = if (vertical) left else top
            val high = if (vertical) right else bottom
            for (lane in 0 until lanes) {
                if (abs(lane - low) > sideBand && abs(lane - high) > sideBand) continue
                fun index(position: Int) = if (vertical) position * width + lane else lane * width + position
                var position = 0
                while (position < length) {
                    if (!candidate[index(position)]) { position++; continue }
                    val start = position
                    var outside = false
                    while (position < length && candidate[index(position)]) {
                        outside = outside || !semantic[index(position)] || position == 0 || position == length - 1
                        position++
                    }
                    // A tall interior stroke is still text. A border must also continue beyond
                    // the recognized text extent (or the available source crop).
                    if (position - start < minimumRun || !outside) continue
                    var samples = 0; var narrowSamples = 0
                    val sampleStep = maxOf(1, (position - start) / 16)
                    for (part in start until position step sampleStep) {
                        var thickness = 1
                        var neighbor = lane - 1
                        while (neighbor >= 0 && thickness <= maximumThickness) {
                            val i = if (vertical) part * width + neighbor else neighbor * width + part
                            if (!candidate[i]) break
                            thickness++; neighbor--
                        }
                        neighbor = lane + 1
                        while (neighbor < lanes && thickness <= maximumThickness) {
                            val i = if (vertical) part * width + neighbor else neighbor * width + part
                            if (!candidate[i]) break
                            thickness++; neighbor++
                        }
                        samples++
                        if (thickness <= maximumThickness) narrowSamples++
                    }
                    // A broad colored illustration is not a panel line. Ignore occasional
                    // wider joins, where a glyph touches an otherwise thin frame.
                    if (narrowSamples * 3 < samples * 2) continue
                    for (part in start until position) lines[index(part)] = true
                }
            }
        }
        scan(vertical = true)
        scan(vertical = false)
        // Keep the antialiased fringe of the line too, so it cannot reconnect a glyph to it.
        return BinarySquareDilation.dilate(lines, width, height, maxOf(1, minorAxis / 100))
    }
}
