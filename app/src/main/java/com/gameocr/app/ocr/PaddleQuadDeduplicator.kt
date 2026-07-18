package com.gameocr.app.ocr

import android.graphics.PointF

internal fun DBPostprocessor.Quad.offsetBy(dx: Float, dy: Float): DBPostprocessor.Quad =
    DBPostprocessor.Quad(
        p0 = paddlePointF(p0.x + dx, p0.y + dy),
        p1 = paddlePointF(p1.x + dx, p1.y + dy),
        p2 = paddlePointF(p2.x + dx, p2.y + dy),
        p3 = paddlePointF(p3.x + dx, p3.y + dy),
    )

internal fun paddlePointF(x: Float, y: Float): PointF = PointF().apply {
    this.x = x
    this.y = y
}

internal fun dedupePaddleQuads(quads: List<DBPostprocessor.Quad>): List<DBPostprocessor.Quad> {
    val out = mutableListOf<DBPostprocessor.Quad>()
    for (quad in quads) {
        if (out.none { existing -> paddleQuadsAreDuplicate(existing, quad) }) {
            out += quad
        }
    }
    return out.sortedWith(compareBy({ it.centerY }, { it.centerX }))
}

internal fun paddleQuadsAreDuplicate(
    a: DBPostprocessor.Quad,
    b: DBPostprocessor.Quad,
): Boolean {
    if (paddleQuadAxisAlignedIou(a, b) >= 0.72f) return true
    val dx = a.centerX - b.centerX
    val dy = a.centerY - b.centerY
    val centerClose = dx * dx + dy * dy <= 16f * 16f
    if (!centerClose) return false
    val areaA = paddleQuadArea(a)
    val areaB = paddleQuadArea(b)
    val ratio = minOf(areaA, areaB) / maxOf(areaA, areaB).coerceAtLeast(1f)
    return ratio >= 0.75f
}

private fun paddleQuadAxisAlignedIou(
    a: DBPostprocessor.Quad,
    b: DBPostprocessor.Quad,
): Float {
    val ar = a.axisAlignedBounds()
    val br = b.axisAlignedBounds()
    val interW = (minOf(ar[2], br[2]) - maxOf(ar[0], br[0])).coerceAtLeast(0)
    val interH = (minOf(ar[3], br[3]) - maxOf(ar[1], br[1])).coerceAtLeast(0)
    val inter = interW * interH
    if (inter <= 0) return 0f
    val areaA = ((ar[2] - ar[0]).coerceAtLeast(0) * (ar[3] - ar[1]).coerceAtLeast(0)).toFloat()
    val areaB = ((br[2] - br[0]).coerceAtLeast(0) * (br[3] - br[1]).coerceAtLeast(0)).toFloat()
    return inter / (areaA + areaB - inter).coerceAtLeast(1f)
}

private fun paddleQuadArea(q: DBPostprocessor.Quad): Float {
    val r = q.axisAlignedBounds()
    return ((r[2] - r[0]).coerceAtLeast(0) * (r[3] - r[1]).coerceAtLeast(0)).toFloat()
}
