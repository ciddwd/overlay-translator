package com.gameocr.app.ocr

import kotlin.math.abs

/** Finds detached ink from the source image, independently of the detector's selected pixels. */
internal object TextRegionInkAudit {
    enum class Reason { NOT_CHECKED, VERIFIED, PARTIAL, INSUFFICIENT_SAMPLES, NO_DOMINANT_BACKGROUND, LOW_CONTRAST }

    data class Result(
        val mask: BooleanArray,
        val addedPixels: Int,
        val reason: Reason,
        val uncertainPixels: Int = 0,
        val protectedPixels: BooleanArray = BooleanArray(mask.size),
        val inkColor: Int? = null,
        val interiorSeedPixels: Int = 0,
        val foregroundPixels: BooleanArray? = null,
        val borderPixels: Int = 0,
        val backgroundColor: Int? = null,
        val outlinePixels: Int = 0,
        val inkColorCount: Int = 0,
    )

    fun inspect(
        width: Int,
        height: Int,
        argb: IntArray,
        core: BooleanArray,
        semantic: BooleanArray,
        erase: BooleanArray,
        barrierPixels: BooleanArray = BooleanArray(0),
    ): Result {
        require(width > 0 && height > 0 && argb.size == width * height)
        require(core.size == argb.size && semantic.size == argb.size && erase.size == argb.size)
        require(barrierPixels.isEmpty() || barrierPixels.size == argb.size)
        fun unchanged(reason: Reason) = Result(erase.copyOf(), 0, reason)

        // An edge or one contaminated component must not invalidate the clean interior of a balloon.
        val histogram = IntArray(4096)
        var sampleCount = 0
        for (i in argb.indices) {
            if (semantic[i] && !erase[i]) {
                val c = argb[i]
                val bin = ((c ushr 20 and 15) shl 8) or ((c ushr 12 and 15) shl 4) or (c ushr 4 and 15)
                histogram[bin]++
                sampleCount++
            }
        }
        if (sampleCount < MIN_SAMPLES) return unchanged(Reason.INSUFFICIENT_SAMPLES)
        val bin = histogram.indices.maxBy { histogram[it] }
        val center = color((bin ushr 8) * 16 + 8, (bin ushr 4 and 15) * 16 + 8, (bin and 15) * 16 + 8)
        var red = 0L; var green = 0L; var blue = 0L; var inliers = 0
        for (i in argb.indices) {
            if (!semantic[i] || erase[i] || distance(argb[i], center) > BACKGROUND_TOLERANCE) continue
            red += argb[i] ushr 16 and 255
            green += argb[i] ushr 8 and 255
            blue += argb[i] and 255
            inliers++
        }
        if (inliers < MIN_SAMPLES || inliers.toFloat() / sampleCount < MIN_BACKGROUND_FRACTION) {
            return unchanged(Reason.NO_DOMINANT_BACKGROUND)
        }
        val background = color((red / inliers).toInt(), (green / inliers).toInt(), (blue / inliers).toInt())
        val spreads = IntArray(256)
        for (i in argb.indices) {
            if (semantic[i] && !erase[i] && distance(argb[i], center) <= BACKGROUND_TOLERANCE) {
                spreads[distance(argb[i], background)]++
            }
        }
        var count = 0
        val spread = spreads.indices.first { count += spreads[it]; count > inliers * 9 / 10 }
        if (spread > MAX_BACKGROUND_SPREAD) return unchanged(Reason.NO_DOMINANT_BACKGROUND)

        val candidate = BooleanArray(argb.size) { distance(argb[it], background) > maxOf(20, spread * 2) }
        val border = if (barrierPixels.isEmpty()) {
            TextRegionBorderMask.detect(width, height, candidate, semantic)
        } else barrierPixels.copyOf()
        val visited = BooleanArray(argb.size)
        val queue = IntArray(argb.size)
        val connectivity = inkConnectivity(width, height, argb, background, candidate, border, semantic, queue)
        val output = erase.copyOf()
        val foreground = BooleanArray(argb.size)
        val protected = border.copyOf()
        val interiorComponents = ArrayList<IntArray>()
        val seeds = IntArray(argb.size)
        var seedCount = 0
        val interiorSeeds = IntArray(argb.size)
        var interiorSeedCount = 0
        var added = 0
        var uncertain = 0
        var outlines = 0
        for (start in candidate.indices) {
            if (!semantic[start] || !candidate[start] || border[start] || visited[start]) continue
            var head = 0; var tail = 1
            var outside = false; var touchesCore = false; var inside = 0
            queue[0] = start
            visited[start] = true
            // Follow the whole component beyond the text box to distinguish glyphs from artwork.
            while (head < tail) {
                val i = queue[head++]
                val x = i % width; val y = i / width
                outside = outside || !semantic[i] || x == 0 || y == 0 || x == width - 1 || y == height - 1
                touchesCore = touchesCore || (semantic[i] && core[i])
                if (semantic[i]) {
                    inside++
                }
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (visited[next] || !candidate[next] || border[next] ||
                        connectivity[next] != connectivity[start]) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            if (outside) {
                if (touchesCore) uncertain += inside
                for (position in 0 until tail) protected[queue[position]] = true
                continue
            }
            val component = queue.copyOf(tail)
            interiorComponents += component
            for (i in component) {
                if (distance(argb[i], background) < MIN_INK_CONTRAST) continue
                interiorSeeds[interiorSeedCount++] = i
                if (core[i]) seeds[seedCount++] = i
            }
        }
        // Detector cores may lie entirely on a character's hand or the bubble outline. Those
        // pixels must never define the ink color for every glyph in the recognized paragraph.
        val confirmedSeeds = when {
            seedCount >= MIN_SEEDS -> seeds.copyOf(seedCount)
            interiorSeedCount >= MIN_SEEDS -> interiorSeeds.copyOf(interiorSeedCount)
            else -> return Result(output, 0, if (uncertain > 0) Reason.PARTIAL else Reason.LOW_CONTRAST,
                uncertain, protected, borderPixels = border.count { it })
        }
        val ink = ArgbChannelMedian.fromIndexedColors(argb, confirmedSeeds)
        val contrast = distance(ink, background)
        if (contrast < MIN_INK_CONTRAST) return unchanged(Reason.LOW_CONTRAST)
        // Each seeded interior glyph can have its own ink color. A paragraph may contain both
        // black text and a large red word; its single median must not protect either of them.
        val directions = ArrayList<InkDirection>()
        fun matchesInk(c: Int): Boolean {
            val r = channel(c, 16) - channel(background, 16)
            val g = channel(c, 8) - channel(background, 8)
            val b = channel(c, 0) - channel(background, 0)
            if (directions.any { direction ->
                val vector = direction.vector
                val projection = (r * vector[0] + g * vector[1] + b * vector[2]) / direction.norm
                projection > 0f && maxOf(abs(r - projection * vector[0]),
                    abs(g - projection * vector[1]), abs(b - projection * vector[2])) <= direction.tolerance
            }) return true
            // A detached colored stroke can be darker or less saturated than the seeded body.
            // RGB interpolation alone rejects these textured edges. Compare chromatic direction
            // independently of brightness, while retaining contrast and light/dark polarity.
            if (distance(c, background) < MIN_INK_CONTRAST || chroma(c) < MIN_INK_CONTRAST) return false
            val chromatic = chromaticVector(c)
            val norm = chromatic.sumOf { it * it }.toFloat()
            val luminanceDelta = luminance(c) - luminance(background)
            return directions.any { direction ->
                val reference = direction.chromatic ?: return@any false
                val dot = (chromatic[0] * reference[0] + chromatic[1] * reference[1] +
                    chromatic[2] * reference[2]).toFloat()
                dot > 0f && luminanceDelta.toFloat() * direction.luminanceDelta > 0f &&
                    dot * dot >= MIN_CHROMATIC_COSINE_SQUARED * norm * direction.chromaticNorm
            }
        }
        fun addInk(c: Int) {
            if (distance(c, background) < MIN_INK_CONTRAST || matchesInk(c)) return
            val vector = intArrayOf(channel(c, 16) - channel(background, 16),
                channel(c, 8) - channel(background, 8), channel(c, 0) - channel(background, 0))
            val chromatic = if (chroma(c) >= MIN_INK_CONTRAST) chromaticVector(c) else null
            directions += InkDirection(vector, vector.sumOf { it * it }.toFloat(),
                maxOf(12f, vector.maxOf { abs(it) } * 0.10f), chromatic,
                chromatic?.sumOf { it * it }?.toFloat() ?: 0f, luminance(c) - luminance(background))
        }
        addInk(ink)
        for (component in interiorComponents) {
            val localSeeds = component.filter { core[it] && distance(argb[it], background) >= MIN_INK_CONTRAST }
            if (localSeeds.size >= MIN_SEEDS) {
                addInk(ArgbChannelMedian.fromIndexedColors(argb, localSeeds.toIntArray()))
            }
        }
        val inkBodies = BooleanArray(argb.size)
        for (component in interiorComponents) for (i in component) inkBodies[i] = matchesInk(argb[i])
        // Pale outlines on colored balloons can be connected to a glyph or separated by an
        // antialiased gap. Keep the same decision in both cases. Only a thin, low-contrast fringe
        // near confirmed interior ink qualifies; an unrelated colored island stays protected.
        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        for (i in semantic.indices) if (semantic[i]) {
            minX = minOf(minX, i % width); maxX = maxOf(maxX, i % width)
            minY = minOf(minY, i / width); maxY = maxOf(maxY, i / width)
        }
        val outlineRadius = maxOf(2, (minOf(maxX - minX + 1, maxY - minY + 1) * 0.06f).toInt())
        val nearInk = BinarySquareDilation.dilate(inkBodies, width, height, outlineRadius)
        for (component in interiorComponents) {
            val explained = component.count { i -> inkBodies[i] ||
                (nearInk[i] && distance(argb[i], background) < MIN_INK_CONTRAST) }
            if (explained.toFloat() / component.size < MIN_INK_FRACTION) {
                uncertain += component.size
                for (i in component) protected[i] = true
                continue
            }
            for (i in component) {
                foreground[i] = true
                if (!inkBodies[i]) outlines++
                if (!output[i]) { output[i] = true; added++ }
            }
        }
        // Exterior artwork has faint edge pixels too. Keep those out of later foreground
        // completion and background sampling, without taking back any confirmed glyph ink.
        val protectedFringe = BinarySquareDilation.dilate(
            protected, width, height, maxOf(1, minOf(maxX - minX + 1, maxY - minY + 1) / 100),
        )
        for (i in protected.indices) {
            if (protectedFringe[i] && !foreground[i] && distance(argb[i], background) > MAX_BACKGROUND_SPREAD) {
                protected[i] = true
            }
        }
        for (i in output.indices) if (protected[i]) output[i] = false
        return Result(output, added, if (uncertain == 0) Reason.VERIFIED else Reason.PARTIAL,
            uncertain, protected, ink, confirmedSeeds.size, foreground, border.count { it }, background,
            outlines, directions.size)
    }

    /**
     * Antialiased fringes can join an otherwise separate glyph to a panel or illustration.
     * Label the contrasting bodies first, then give faint pixels to their nearest body. This
     * retains the fringe without letting a weak gray bridge protect the entire word as artwork.
     */
    private fun inkConnectivity(
        width: Int,
        height: Int,
        argb: IntArray,
        background: Int,
        candidate: BooleanArray,
        border: BooleanArray,
        semantic: BooleanArray,
        queue: IntArray,
    ): IntArray {
        val labels = IntArray(argb.size)
        val outsideLabels = arrayListOf(false)
        for (start in labels.indices) {
            if (labels[start] != 0 || !candidate[start] || border[start] ||
                distance(argb[start], background) < MIN_INK_CONTRAST) continue
            val label = outsideLabels.size
            var outside = false
            var head = 0; var tail = 1
            queue[0] = start
            labels[start] = label
            while (head < tail) {
                val i = queue[head++]
                val x = i % width; val y = i / width
                outside = outside || !semantic[i] || x == 0 || y == 0 || x == width - 1 || y == height - 1
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (labels[next] != 0 || !candidate[next] || border[next] ||
                        distance(argb[next], background) < MIN_INK_CONTRAST) continue
                    labels[next] = label
                    queue[tail++] = next
                }
            }
            outsideLabels += outside
        }
        var head = 0; var tail = 0
        // Protected exterior components win equal-distance fringe pixels.
        for (outside in listOf(true, false)) for (i in labels.indices) {
            if (labels[i] != 0 && outsideLabels[labels[i]] == outside) queue[tail++] = i
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % width; val y = i / width
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (labels[next] != 0 || !candidate[next] || border[next]) continue
                labels[next] = labels[i]
                queue[tail++] = next
            }
        }
        return labels
    }

    private fun channel(color: Int, shift: Int): Int = color ushr shift and 255
    private data class InkDirection(val vector: IntArray, val norm: Float, val tolerance: Float,
        val chromatic: IntArray?, val chromaticNorm: Float, val luminanceDelta: Int)
    private fun chroma(c: Int): Int = maxOf(channel(c, 16), channel(c, 8), channel(c, 0)) -
        minOf(channel(c, 16), channel(c, 8), channel(c, 0))
    private fun chromaticVector(c: Int): IntArray {
        val r = channel(c, 16); val g = channel(c, 8); val b = channel(c, 0)
        return intArrayOf(2 * r - g - b, 2 * g - r - b, 2 * b - r - g)
    }
    private fun luminance(c: Int): Int = channel(c, 16) * 299 + channel(c, 8) * 587 + channel(c, 0) * 114
    private fun color(r: Int, g: Int, b: Int): Int = -0x1000000 or
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun distance(a: Int, b: Int): Int = maxOf(abs(channel(a, 16) - channel(b, 16)),
        abs(channel(a, 8) - channel(b, 8)), abs(channel(a, 0) - channel(b, 0)))

    private const val MIN_SAMPLES = 16
    private const val MIN_SEEDS = 4
    private const val BACKGROUND_TOLERANCE = 20
    private const val MAX_BACKGROUND_SPREAD = 12
    private const val MIN_BACKGROUND_FRACTION = 0.70f
    private const val MIN_INK_FRACTION = 0.90f
    private const val MIN_INK_CONTRAST = 40
    private const val MIN_CHROMATIC_COSINE_SQUARED = 0.96f
}
