package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBubbleBackgroundRepairerTest {

    @Test
    fun `shape patch eligibility requires complete repair for each bubble`() {
        data class Case(
            val name: String,
            val crops: List<LocalBubbleBackgroundRepairer.CropMetric>,
            val expectedModelIndices: Set<Int>,
        )

        fun crop(
            modelIndex: Int,
            repairedPixels: Int,
            acceptedComponents: Int,
            totalComponents: Int,
        ) = LocalBubbleBackgroundRepairer.CropMetric(
            modelBubbleIndex = modelIndex,
            bounds = IntRect(0, 0, 10, 10),
            erasePixels = 20,
            repairedPixels = repairedPixels,
            acceptedComponentCount = acceptedComponents,
            componentCount = totalComponents,
        )

        val cases = listOf(
            Case(
                name = "complete repair is eligible",
                crops = listOf(crop(0, repairedPixels = 20, acceptedComponents = 2, totalComponents = 2)),
                expectedModelIndices = setOf(0),
            ),
            Case(
                name = "partial repair is rejected",
                crops = listOf(crop(1, repairedPixels = 12, acceptedComponents = 1, totalComponents = 2)),
                expectedModelIndices = emptySet(),
            ),
            Case(
                name = "complete and partial bubbles are independent",
                crops = listOf(
                    crop(2, repairedPixels = 18, acceptedComponents = 2, totalComponents = 2),
                    crop(3, repairedPixels = 9, acceptedComponents = 1, totalComponents = 2),
                ),
                expectedModelIndices = setOf(2),
            ),
            Case(
                name = "zero components are not eligible",
                crops = listOf(crop(4, repairedPixels = 0, acceptedComponents = 0, totalComponents = 0)),
                expectedModelIndices = emptySet(),
            ),
            Case(
                name = "zero repaired pixels are not eligible",
                crops = listOf(crop(5, repairedPixels = 0, acceptedComponents = 1, totalComponents = 1)),
                expectedModelIndices = emptySet(),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedModelIndices,
                LocalBubbleBackgroundRepairer.fullyRepairedModelIndices(case.crops),
            )
        }
    }

    @Test
    fun `crop planner expands content and clips to image and model bounds`() {
        data class Case(
            val name: String,
            val imageWidth: Int,
            val imageHeight: Int,
            val members: List<IntRect>,
            val model: BubbleSegmentationPostprocessor.InstanceMask,
            val expected: IntRect?,
        )

        val cases = listOf(
            Case(
                name = "regular crop",
                imageWidth = 100,
                imageHeight = 100,
                members = listOf(IntRect(20, 20, 30, 30)),
                model = solidMask(5, 5, 40, 40),
                expected = IntRect(8, 8, 42, 42),
            ),
            Case(
                name = "image edge",
                imageWidth = 100,
                imageHeight = 100,
                members = listOf(IntRect(0, 0, 8, 8)),
                model = solidMask(0, 0, 20, 20),
                expected = IntRect(0, 0, 20, 20),
            ),
            Case(
                name = "tight model bounds",
                imageWidth = 100,
                imageHeight = 100,
                members = listOf(IntRect(30, 30, 40, 40)),
                model = solidMask(34, 35, 4, 3),
                expected = IntRect(34, 35, 38, 38),
            ),
            Case(
                name = "no content and model overlap",
                imageWidth = 100,
                imageHeight = 100,
                members = listOf(IntRect(60, 60, 70, 70)),
                model = solidMask(0, 0, 20, 20),
                expected = null,
            ),
        )

        cases.forEach { case ->
            val actual = LocalBubbleBackgroundRepairer.planCrop(
                imageWidth = case.imageWidth,
                imageHeight = case.imageHeight,
                memberBounds = case.members,
                modelMask = case.model,
            )
            if (case.expected == null) {
                assertNull(case.name, actual)
            } else {
                assertNotNull(case.name, actual)
                assertEquals(case.name, case.expected, actual)
            }
        }
    }

    @Test
    fun `two flat bubbles are repaired with a small working pixel ratio`() {
        val width = 200
        val height = 200
        val white = argb(255, 248, 248, 248)
        val black = argb(255, 10, 10, 10)
        val source = IntArray(width * height) { white }
        val erase = BooleanArray(source.size)
        fillRect(width, source, erase, IntRect(22, 22, 28, 28), black)
        fillRect(width, source, erase, IntRect(142, 142, 148, 148), black)

        val result = LocalBubbleBackgroundRepairer.repair(
            width = width,
            height = height,
            sourceArgb = source,
            eraseMask = erase,
            regions = listOf(
                region(0, IntRect(20, 20, 30, 30), solidMask(5, 5, 45, 45)),
                region(1, IntRect(140, 140, 150, 150), solidMask(125, 125, 45, 45)),
            ),
        )

        assertEquals(2, result.cropCount)
        assertEquals(2, result.repairResult.acceptedComponentCount)
        assertTrue(result.totalWorkingPixels < width * height / 10)
        assertTrue(result.workingPixelRatio < 0.1f)
        assertEquals(white, result.repairResult.pixels[25 * width + 25])
        assertEquals(white, result.repairResult.pixels[145 * width + 145])
    }

    @Test
    fun `erase pixels outside the assigned local member crop stay untouched`() {
        val width = 100
        val height = 100
        val white = argb(255, 250, 250, 250)
        val black = argb(255, 8, 8, 8)
        val source = IntArray(width * height) { white }
        val erase = BooleanArray(source.size)
        fillRect(width, source, erase, IntRect(22, 22, 28, 28), black)
        fillRect(width, source, erase, IntRect(70, 70, 76, 76), black)

        val result = LocalBubbleBackgroundRepairer.repair(
            width = width,
            height = height,
            sourceArgb = source,
            eraseMask = erase,
            regions = listOf(
                region(0, IntRect(20, 20, 30, 30), solidMask(0, 0, width, height)),
            ),
        )

        assertEquals(white, result.repairResult.pixels[25 * width + 25])
        assertEquals(black, result.repairResult.pixels[73 * width + 73])
        assertFalse(result.repairResult.repairedMask[73 * width + 73])
    }

    @Test
    fun `complex local background is rejected without changing source pixels`() {
        val width = 80
        val height = 80
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x + y) % 2 == 0) {
                argb(255, 30, 30, 30)
            } else {
                argb(255, 230, 230, 230)
            }
        }
        val erase = BooleanArray(source.size)
        val original = argb(255, 80, 80, 80)
        fillRect(width, source, erase, IntRect(32, 32, 40, 40), original)

        val result = LocalBubbleBackgroundRepairer.repair(
            width = width,
            height = height,
            sourceArgb = source,
            eraseMask = erase,
            regions = listOf(
                region(0, IntRect(30, 30, 42, 42), solidMask(10, 10, 60, 60)),
            ),
        )

        assertEquals(0, result.repairResult.acceptedComponentCount)
        assertTrue(result.repairResult.rejectedComponentCount > 0)
        assertEquals(original, result.repairResult.pixels[35 * width + 35])
        assertFalse(result.repairResult.repairedMask[35 * width + 35])
    }

    private fun region(
        index: Int,
        member: IntRect,
        model: BubbleSegmentationPostprocessor.InstanceMask,
    ) = LocalBubbleBackgroundRepairer.Region(
        modelBubbleIndex = index,
        memberBounds = listOf(member),
        modelMask = model,
    )

    private fun solidMask(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) = BubbleSegmentationPostprocessor.InstanceMask(
        left = left,
        top = top,
        width = width,
        height = height,
        pixels = BooleanArray(width * height) { true },
    )

    private fun fillRect(
        width: Int,
        pixels: IntArray,
        mask: BooleanArray,
        bounds: IntRect,
        color: Int,
    ) {
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val index = y * width + x
                pixels[index] = color
                mask[index] = true
            }
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
