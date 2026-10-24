package com.gameocr.app.ocr

import kotlin.math.ceil

/** Recover outlined ink on textured manga backgrounds, instead of erasing a DBNet rectangle. */
internal object TextStrokeMaskPolicy {
    data class Plan(val ink: BooleanArray, val erase: BooleanArray, val protected: BooleanArray)

    fun plan(width: Int, height: Int, argb: IntArray, core: BooleanArray, semantic: BooleanArray,
        allowChromaticInk: Boolean = false,
        diagnostic: ((BubbleClusterer.IntRect, String) -> Unit)? = null): Plan? {
        require(width > 0 && height > 0 && argb.size == width * height)
        require(core.size == argb.size && semantic.size == argb.size)
        // This fallback only explains grayscale ink. A partial grayscale plan must not
        // replace a detector mask that also contains colored glyphs/background evidence.
        if (!allowChromaticInk && core.indices.count { core[it] && !grayscale(argb[it]) } >= 4) return null
        val ink = BooleanArray(argb.size)
        val erase = BooleanArray(argb.size)
        val frames = TextRegionBorderMask.detect(width, height,
            BooleanArray(argb.size) { grayscale(argb[it]) && luma(argb[it]) < 90 }, semantic)
        val queue = IntArray(argb.size)
        val evidenceRadius = (minOf(width, height) / 40).coerceIn(3, 8)
        val nearbyCore = BinarySquareDilation.dilate(core, width, height, evidenceRadius)
        for (mode in 0..if(allowChromaticInk) 2 else 1) {
            val light=mode==1
            val colored=mode==2
            val candidate = BooleanArray(argb.size) { i ->
                val c = argb[i]
                val gray = maxOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) -
                    minOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) <= 24
                if(colored) !gray && luma(c)<190 else gray && if (light) luma(c) >= 225 else luma(c) <= 160
            }
            val visited = BooleanArray(argb.size)
            for (start in candidate.indices) {
                if (!candidate[start] || visited[start] || !semantic[start] || frames[start]) continue
                var head = 0; var tail = 1
                var outside = false; var seeds = 0
                var left = width; var right = 0; var top = height; var bottom = 0
                queue[0] = start; visited[start] = true
                while (head < tail) {
                    val i = queue[head++]; val x = i % width; val y = i / width
                    outside = outside || !semantic[i] || x == 0 || x == width - 1 || y == 0 || y == height - 1
                    if (nearbyCore[i]) seeds++
                    left = minOf(left, x); right = maxOf(right, x + 1)
                    top = minOf(top, y); bottom = maxOf(bottom, y + 1)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (visited[next] || !candidate[next] || frames[next] ||
                            (colored && !sameChroma(argb[start],argb[next]))) continue
                        visited[next] = true; queue[tail++] = next
                    }
                }
                val bounds = BubbleClusterer.IntRect(left, top, right, bottom)
                if (outside || tail < 2 || seeds < 2 || seeds < tail * 0.30f) {
                    diagnostic?.invoke(bounds, "light=$light outside=$outside pixels=$tail seeds=$seeds")
                    continue
                }
                var boundary = 0; var outlined = 0
                for (position in 0 until tail) {
                    val i = queue[position]; val x = i % width; val y = i / width
                    if (x > 0 && x + 1 < width && y > 0 && y + 1 < height &&
                        candidate[i-1] && candidate[i+1] && candidate[i-width] && candidate[i+width]) continue
                    boundary++
                    var opposite = false
                    for (dy in -2..2) for (dx in -2..2) {
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val value = luma(argb[ny * width + nx])
                        if (if (light) value < 90 else value > 210) opposite = true
                    }
                    if (opposite) outlined++
                }
                // An interior stroke may be printed directly on a tone, with no white halo.
                // Requiring an opposite-color edge misses those strokes and the interior
                // holes of dense kanji. The detector seed and outside/frame checks above
                // establish the text evidence; edge contrast only controls outline expansion.
                diagnostic?.invoke(bounds, "accepted light=$light pixels=$tail seeds=$seeds")
                // Include the opposite-color outline and antialiasing around this glyph, not
                // the entire word/paragraph search rectangle or the gaps between its columns.
                val radius = if (boundary > 0 && outlined >= boundary * 0.30f)
                    ceil(maxOf(right-left, bottom-top) * 0.25f).toInt()
                        .coerceIn(2, maxOf(2, minOf(width, height) / 8)) else 1
                for (position in 0 until tail) {
                    val i = queue[position]; val x = i % width; val y = i / width
                    ink[i] = true
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        val c = argb[next]
                        val gray = maxOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) -
                            minOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) <= 24
                        if (semantic[next] && !frames[next] && (gray ||
                                (colored && luma(c)<190 && sameChroma(argb[start],c)))) erase[next] = true
                    }
                }
            }
        }
        // Include complete, bounded white outlines. Dilating only the dark stroke leaves
        // its outer halo available as a background sample, producing white glyph-shaped
        // patches. A page or open white balloon reaches the crop boundary and is excluded.
        val nearInk = BinarySquareDilation.dilate(ink, width, height,
            if(allowChromaticInk) (minOf(width,height)/30).coerceIn(3,10) else 3)
        val white = BooleanArray(argb.size) { grayscale(argb[it]) && luma(argb[it]) >= 210 }
        val whiteEdge = BinarySquareDilation.dilate(BooleanArray(white.size) { !white[it] },
            width, height, evidenceRadius)
        val seen = BooleanArray(argb.size)
        for (start in white.indices) {
            if (!white[start] || seen[start]) continue
            var head = 0; var tail = 1; var outside = false; var adjacent = 0; var inside = 0; var seeds = 0
            queue[0] = start; seen[start] = true
            while (head < tail) {
                val i = queue[head++]; val x = i % width; val y = i / width
                outside = outside || x == 0 || y == 0 || x == width - 1 || y == height - 1 || frames[i]
                if (nearInk[i]) adjacent++
                if (semantic[i]) inside++
                if (core[i]) seeds++
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (seen[next] || !white[next]) continue
                    seen[next] = true; queue[tail++] = next
                }
            }
            if (outside) {
                // An outline can touch a large white drawing/background. Retain only its
                // thin, detector-supported edge; never flood that connected white field.
                for (position in 0 until tail) {
                    val i = queue[position]
                    if (whiteEdge[i] && (if(allowChromaticInk)nearInk[i] else nearbyCore[i]) &&
                        semantic[i] && !frames[i]) erase[i] = true
                }
                continue
            }
            if ((adjacent < 4 && seeds < 4) || inside < tail * 0.70f) continue
            for (position in 0 until tail) {
                val i=queue[position]
                if(!allowChromaticInk || adjacent>=tail*.65f || (nearInk[i] && whiteEdge[i]))erase[i] = true
            }
        }
        // Cover the antialiased rim of the accepted stroke/outline without enlarging the
        // repair to the detector's whole paragraph rectangle.
        val fringe = BinarySquareDilation.dilate(erase, width, height,
            (minOf(width, height) / 100).coerceIn(2, 4))
        for (i in erase.indices) if (fringe[i] && semantic[i] && !frames[i] && grayscale(argb[i])) erase[i] = true
        return if (ink.count { it } >= 16) Plan(ink, erase, frames) else null
    }

    private fun luma(c: Int) = ((c ushr 16 and 255) * 299 +
        (c ushr 8 and 255) * 587 + (c and 255) * 114) / 1000

    private fun grayscale(c: Int) = maxOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) -
        minOf(c ushr 16 and 255, c ushr 8 and 255, c and 255) <= 24

    private fun sameChroma(a:Int,b:Int):Boolean {
        if(grayscale(a) || grayscale(b))return false
        val amin=minOf(a ushr 16 and 255,a ushr 8 and 255,a and 255)
        val bmin=minOf(b ushr 16 and 255,b ushr 8 and 255,b and 255)
        val av=intArrayOf((a ushr 16 and 255)-amin,(a ushr 8 and 255)-amin,(a and 255)-amin)
        val bv=intArrayOf((b ushr 16 and 255)-bmin,(b ushr 8 and 255)-bmin,(b and 255)-bmin)
        val dot=av.indices.sumOf { av[it]*bv[it] }.toDouble()
        return dot*dot>=av.sumOf { it*it }.toDouble()*bv.sumOf { it*it }*.85
    }
}
