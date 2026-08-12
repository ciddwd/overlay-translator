package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRepairSolidCoverageTest {

    @Test
    fun plan_tableDriven_preservesFormerOpaqueCoverageWithoutTranslucentBand() {
        data class Case(
            val name: String,
            val baseRadius: Int,
            val coordinateScale: Float,
            val expectedSolidExpansionPx: Int,
        )

        listOf(
            Case("small glyph mask", baseRadius = 1, coordinateScale = 1f, expectedSolidExpansionPx = 1),
            Case("medium glyph mask", baseRadius = 4, coordinateScale = 1f, expectedSolidExpansionPx = 2),
            Case("large outlined glyph", baseRadius = 8, coordinateScale = 1f, expectedSolidExpansionPx = 3),
            Case("same medium glyph at 2x repair scale", baseRadius = 8, coordinateScale = 2f, expectedSolidExpansionPx = 4),
        ).forEach { case ->
            val core = diskMask(radius = 0)
            val base = diskMask(radius = case.baseRadius)
            val plan = TextRepairSolidCoverage.plan(
                width = WIDTH,
                height = HEIGHT,
                baseMask = base,
                coreMask = core,
                coordinateScale = case.coordinateScale,
            )

            assertEquals(case.name, case.baseRadius, plan.existingExpansionPx)
            assertEquals(case.name, case.expectedSolidExpansionPx, plan.solidExpansionPx)
            assertTrue(case.name, plan.repairPixelCount > base.count { it })
            base.indices.filter { base[it] }.forEach { index ->
                assertTrue("${case.name}: base coverage is preserved", plan.repairMask[index])
            }
        }
    }

    @Test
    fun plan_tableDriven_usesEuclideanSolidExpansionAndClipsAtBounds() {
        data class Case(val name: String, val centerX: Int, val centerY: Int)

        listOf(
            Case("center", CENTER, CENTER),
            Case("top left", 0, 0),
            Case("bottom right", WIDTH - 1, HEIGHT - 1),
        ).forEach { case ->
            val core = BooleanArray(WIDTH * HEIGHT).apply {
                this[case.centerY * WIDTH + case.centerX] = true
            }
            val plan = TextRepairSolidCoverage.plan(
                width = WIDTH,
                height = HEIGHT,
                baseMask = core,
                coreMask = core,
                coordinateScale = 1f,
            )

            assertTrue(case.name, plan.repairMask[case.centerY * WIDTH + case.centerX])
            plan.repairMask.indices.filter { plan.repairMask[it] }.forEach { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                val dx = x - case.centerX
                val dy = y - case.centerY
                assertTrue("${case.name}: no square-corner over-expansion", dx * dx + dy * dy <= 1)
            }
        }
    }

    @Test
    fun plan_doesNotMutateInputMasks() {
        val core = diskMask(radius = 0)
        val base = diskMask(radius = 4)
        val coreBefore = core.copyOf()
        val baseBefore = base.copyOf()

        TextRepairSolidCoverage.plan(WIDTH, HEIGHT, base, core, coordinateScale = 1f)

        assertTrue(coreBefore.contentEquals(core))
        assertTrue(baseBefore.contentEquals(base))
        assertFalse(base === baseBefore)
    }

    private fun diskMask(radius: Int): BooleanArray = BooleanArray(WIDTH * HEIGHT).apply {
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= radius * radius) {
                    this[(CENTER + dy) * WIDTH + CENTER + dx] = true
                }
            }
        }
    }

    private companion object {
        const val WIDTH = 49
        const val HEIGHT = 49
        const val CENTER = 24
    }
}
