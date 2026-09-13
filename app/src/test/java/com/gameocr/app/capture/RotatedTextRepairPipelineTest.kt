package com.gameocr.app.capture

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import com.gameocr.app.ocr.DelayedTextEraseMaskBuilder
import com.gameocr.app.ocr.LocalTextBackgroundRepairer
import com.gameocr.app.ocr.ShapeAwareBubblePatch
import com.gameocr.app.ocr.TextPixelMaskBuilder
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RotatedTextRepairPipelineTest {
    @Test
    fun repairThenRestore_tableDriven_coversOriginalInkWithScaleAndRegionOffset() {
        for ((baseW, baseH) in listOf(64 to 96, 96 to 64)) {
            for (scale in listOf(1, 2)) for (angle in listOf(0, 90, 180, 270)) {
                for (lightText in listOf(false, true)) {
                    val w = baseW * scale
                    val h = baseH * scale
                    val originalW = if (angle % 180 == 0) w else h
                    val originalH = if (angle % 180 == 0) h else w
                    val bg = if (lightText) 0xff181818.toInt() else 0xfff8f8f8.toInt()
                    val ink = if (lightText) 0xffeeeeee.toInt() else 0xff080808.toInt()
                    val boxes = listOf(IntRect(15 * scale, 20 * scale, 19 * scale, 40 * scale),
                        IntRect(29 * scale, 24 * scale, 33 * scale, 45 * scale))
                    val mask = BooleanArray(w * h)
                    boxes.forEach { box ->
                        for (y in box.top until box.bottom) for (x in box.left until box.right) mask[y * w + x] = true
                    }
                    val source = IntArray(w * h) { if (mask[it]) ink else bg }
                    val masks = TextPixelMaskBuilder.build(w, h, mask,
                        listOf(DelayedTextEraseMaskBuilder.ConfirmedBlock(2, boxes, IntRect(12 * scale, 17 * scale, 36 * scale, 48 * scale))))
                    assertEquals(1, masks.masks.size)
                    val repaired = LocalTextBackgroundRepairer.repair(w, h, source, masks.masks, scale.toFloat()).blocks.single()
                    assertTrue("angle=$angle scale=$scale light=$lightText", repaired.displayable)
                    val patch = ShapeAwareBubblePatch(null, repaired.mask.bounds,
                        requireNotNull(repaired.patchPixels), scale.toFloat(), listOf(2), ShapeAwareBubblePatch.Role.TEXT_BACKGROUND)
                    val restored = CaptureContentOrientationPolicy.mapPatchesToOriginal(listOf(patch), originalW, originalH, angle).single()
                    for (y in 0 until h) for (x in 0 until w) {
                        if (!mask[y * w + x]) continue
                        val dx = when (angle) { 90 -> originalW - 1 - y; 180 -> originalW - 1 - x; 270 -> y; else -> x }
                        val dy = when (angle) { 90 -> x; 180 -> originalH - 1 - y; 270 -> originalH - 1 - x; else -> y }
                        assertTrue(dx in restored.bounds.left until restored.bounds.right)
                        assertTrue(dy in restored.bounds.top until restored.bounds.bottom)
                        val pixel = restored.pixels[(dy - restored.bounds.top) * restored.bounds.width + dx - restored.bounds.left]
                        assertEquals("angle=$angle source=($x,$y) restored=($dx,$dy)", bg, pixel)
                        // Overlay placement adds the capture region offset after inverse rotation/scale.
                        val screenBounds = restored.displayBounds()
                        assertTrue(dx / scale + 7 in (screenBounds.left + 7) until (screenBounds.right + 7))
                        assertTrue(dy / scale + 383 in (screenBounds.top + 383) until (screenBounds.bottom + 383))
                    }
                    assertEquals(listOf(2), restored.blockIndices)
                    assertEquals(patch.pixels.count { it ushr 24 != 0 }, restored.pixels.count { it ushr 24 != 0 })
                    assertFalse("outside repair remains transparent", restored.pixels.all { it ushr 24 != 0 })
                }
            }
        }
    }

    @Test
    fun runtimeWiring_keepsMasksInOcrSpaceAndTransformsBothDisplayPhases() {
        val service = source("service/CaptureService.kt")
        assertFalse(service.contains("shape-aware OCR-space mask skipped for content rotation="))
        assertTrue(service.contains("imageWidth = delayedMaskOcrImageWidth"))
        assertTrue(service.contains("blocks = maskCoordinateBlocks"))
        assertTrue(service.contains("CaptureContentOrientationPolicy.mapPatchesToOriginal("))
        assertTrue(service.contains("requireNotNull(delayedMaskRenderSession).mapPatchesToDisplay("))
        assertTrue(service.contains("session.mapPatchesToDisplay(patches)"))
        val overlay = source("overlay/OverlayManager.kt")
        assertEquals(2, Regex("OverlayPresentationGeometry.captureLocalRectsToView").findAll(overlay).count())
        assertTrue(overlay.contains("view.pivotX = 0f"))
        assertTrue(overlay.contains("view.pivotY = 0f"))
        assertTrue(overlay.contains("finalLeft = requireNotNull(rotatedLayout).left"))
        assertTrue(overlay.contains("leftMargin = displayBounds.left + regionOffset.x"))
        assertTrue(overlay.contains("topMargin = displayBounds.top + regionOffset.y"))
    }

    private fun source(path: String): String {
        val relative = "src/main/java/com/gameocr/app/$path"
        return listOf(File(relative), File("app", relative)).first(File::isFile).readText()
    }
}
