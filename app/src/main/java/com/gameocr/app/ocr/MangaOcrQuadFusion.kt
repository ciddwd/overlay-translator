package com.gameocr.app.ocr

/**
 * Combines the stable full-page DBNet pass with higher-resolution tiled detections.
 *
 * Full-page boxes are structural anchors. A tiled box that touches an anchor is ambiguous: it may
 * be a duplicate, a partial line, or a false positive that bridges two speech bubbles. The anchor
 * already covers that area, so only spatially independent tiled boxes are allowed as supplements.
 */
internal fun fuseMangaOcrQuads(
    base: List<DBPostprocessor.Quad>,
    tiled: List<DBPostprocessor.Quad>,
    anchorGuardGap: Int,
): List<DBPostprocessor.Quad> {
    val anchors = dedupePaddleQuads(base)
    val tiledCandidates = dedupePaddleQuads(tiled)
    val supplements = tiledCandidates.filter { candidate ->
        anchors.none { anchor ->
            paddleQuadsWithinAxisGap(anchor, candidate, anchorGuardGap)
        }
    }
    return dedupePaddleQuads(anchors + supplements)
}

internal fun paddleQuadsWithinAxisGap(
    first: DBPostprocessor.Quad,
    second: DBPostprocessor.Quad,
    gap: Int,
): Boolean {
    val a = first.axisAlignedBounds()
    val b = second.axisAlignedBounds()
    val safeGap = gap.coerceAtLeast(0)
    return axisGap(a[0], a[2], b[0], b[2]) <= safeGap &&
        axisGap(a[1], a[3], b[1], b[3]) <= safeGap
}

private fun axisGap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
    when {
        aEnd < bStart -> bStart - aEnd
        bEnd < aStart -> aStart - bEnd
        else -> 0
    }
