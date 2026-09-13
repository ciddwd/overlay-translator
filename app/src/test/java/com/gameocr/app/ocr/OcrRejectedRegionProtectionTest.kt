package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import com.gameocr.app.capture.CaptureContentOrientationPolicy
import org.junit.Assert.*
import org.junit.Test

class OcrRejectedRegionProtectionTest {
    @Test fun alpha_protection_clips_only_rejected_regions_and_never_mutates_shared_pixels() {
        for (scale in listOf(.5f, 1f)) for (offset in listOf(0, 100)) {
            val bounds = IntRect(offset, offset, offset + 10, offset + 10)
            val original = ShapeAwareBubblePatch(null, bounds, IntArray(100) { -1 }, scale, listOf(0), ShapeAwareBubblePatch.Role.TEXT_BACKGROUND)
            val protected = original.protectSourceRegions(listOf(IntRect(offset + 7, offset - 5, offset + 20, offset + 3)))
            assertEquals(9, protected.pixels.count { it == 0 })
            assertTrue(original.pixels.all { it == -1 })
            assertEquals(original.bounds, protected.bounds)
            assertEquals(original.coordinateScale, protected.coordinateScale)
            assertSame(original, original.protectSourceRegions(emptyList()))
            assertSame(original, original.protectSourceRegions(listOf(IntRect(offset + 10, offset, offset + 20, offset + 5))))
        }
    }

    @Test fun protection_survives_all_capture_orientation_transforms() {
        val original = ShapeAwareBubblePatch(null, IntRect(3, 4, 13, 14), IntArray(100) { -1 }, 1f,
            listOf(0), ShapeAwareBubblePatch.Role.TEXT_BACKGROUND)
        val protected = original.protectSourceRegions(listOf(IntRect(5, 7, 9, 10)))
        for (angle in listOf(0, 90, 180, 270)) {
            val mapped = CaptureContentOrientationPolicy.mapPatchesToOriginal(listOf(protected), 30, 30, angle).single()
            assertEquals("angle=$angle", 12, mapped.pixels.count { it == 0 })
            assertEquals(88, mapped.pixels.count { it == -1 })
        }
    }
}
