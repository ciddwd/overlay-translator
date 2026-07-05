package com.gameocr.app.ocr

internal data class OcrTile(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = (right - left).coerceAtLeast(1)

    val height: Int
        get() = (bottom - top).coerceAtLeast(1)
}

internal object MangaOcrTiling {
    fun shouldUseTiles(width: Int, height: Int, tileSide: Int = DEFAULT_TILE_SIDE): Boolean =
        maxOf(width, height) > tileSide

    fun tilesFor(
        width: Int,
        height: Int,
        tileSide: Int = DEFAULT_TILE_SIDE,
        overlap: Int = DEFAULT_OVERLAP
    ): List<OcrTile> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeTile = tileSide.coerceAtLeast(1)
        val safeOverlap = overlap.coerceIn(0, safeTile - 1)
        val xs = startsFor(safeWidth, safeTile, safeOverlap)
        val ys = startsFor(safeHeight, safeTile, safeOverlap)
        return ys.flatMap { y ->
            xs.map { x ->
                OcrTile(
                    left = x,
                    top = y,
                    right = (x + safeTile).coerceAtMost(safeWidth),
                    bottom = (y + safeTile).coerceAtMost(safeHeight)
                )
            }
        }
    }

    internal fun startsFor(length: Int, tileSide: Int, overlap: Int): List<Int> {
        val safeLength = length.coerceAtLeast(1)
        val safeTile = tileSide.coerceAtLeast(1)
        if (safeLength <= safeTile) return listOf(0)
        val safeOverlap = overlap.coerceIn(0, safeTile - 1)
        val step = (safeTile - safeOverlap).coerceAtLeast(1)
        val starts = mutableListOf<Int>()
        var start = 0
        while (start + safeTile < safeLength) {
            starts += start
            start += step
        }
        val last = safeLength - safeTile
        if (starts.lastOrNull() != last) starts += last
        return starts
    }

    const val DEFAULT_TILE_SIDE = 1800
    const val DEFAULT_OVERLAP = 360
}
