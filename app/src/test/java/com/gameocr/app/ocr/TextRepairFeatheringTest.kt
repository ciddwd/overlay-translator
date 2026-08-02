package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRepairFeatheringTest {

    @Test
    fun plan_tableDriven_derivesEquivalentDisplayFeatherFromMaskScale() {
        data class Case(
            val name: String,
            val baseRadius: Int,
            val coordinateScale: Float,
            val expectedDisplayFeatherPx: Float,
        )

        val cases = listOf(
            Case("small glyph mask", baseRadius = 1, coordinateScale = 1f, expectedDisplayFeatherPx = 1f),
            Case("medium glyph mask", baseRadius = 4, coordinateScale = 1f, expectedDisplayFeatherPx = 2f),
            Case("same medium glyph at 2x repair scale", baseRadius = 8, coordinateScale = 2f, expectedDisplayFeatherPx = 2f),
        )

        cases.forEach { case ->
            val core = diskMask(radius = 0)
            val base = diskMask(radius = case.baseRadius)
            val plan = TextRepairFeathering.plan(
                width = WIDTH,
                height = HEIGHT,
                baseMask = base,
                coreMask = core,
                coordinateScale = case.coordinateScale,
            )

            assertEquals(case.name, case.baseRadius, plan.existingExpansionPx)
            assertEquals(
                case.name,
                case.expectedDisplayFeatherPx,
                plan.featherWidthPx / case.coordinateScale,
                0.001f,
            )
            assertTrue("${case.name}: hard coverage grows beyond the previous mask", plan.opaquePixelCount > base.count { it })
            assertTrue("${case.name}: feather grows beyond hard coverage", plan.repairPixelCount > plan.opaquePixelCount)
        }
    }

    @Test
    fun plan_tableDriven_keepsFullCoverageThenMonotonicallyFadesOutside() {
        data class Case(val name: String, val radius: Int)
        val cases = listOf(
            Case("small antialias edge", radius = 1),
            Case("thick outlined glyph", radius = 4),
            Case("maximum expected detector expansion", radius = 8),
        )

        cases.forEach { case ->
            val core = diskMask(radius = 0)
            val base = diskMask(radius = case.radius)
            val plan = TextRepairFeathering.plan(
                width = WIDTH,
                height = HEIGHT,
                baseMask = base,
                coreMask = core,
                coordinateScale = 1f,
            )

            base.indices.filter { base[it] }.forEach { index ->
                assertEquals("${case.name}: existing erase coverage stays opaque", 255, plan.alpha[index])
            }
            plan.opaqueMask.indices.filter { plan.opaqueMask[it] }.forEach { index ->
                assertEquals("${case.name}: hard expansion is opaque", 255, plan.alpha[index])
            }
            plan.repairMask.indices.filter { plan.repairMask[it] && !plan.opaqueMask[it] }.forEach { index ->
                assertTrue("${case.name}: feather alpha is partial", plan.alpha[index] in 1..254)
            }
            plan.repairMask.indices.filterNot { plan.repairMask[it] }.forEach { index ->
                assertEquals("${case.name}: pixels outside the repair domain stay transparent", 0, plan.alpha[index])
            }

            val row = CENTER * WIDTH
            val innerEdgeX = CENTER + case.radius + plan.hardExpansionPx
            val alphas = (0..plan.featherWidthPx + 1).map { offset ->
                plan.alpha[row + innerEdgeX + offset]
            }
            assertEquals("${case.name}: hard edge starts opaque", 255, alphas.first())
            assertEquals("${case.name}: outside feather is transparent", 0, alphas.last())
            alphas.zipWithNext().forEach { (near, far) ->
                assertTrue("${case.name}: alpha must not increase outwards", near >= far)
            }
        }
    }

    @Test
    fun plan_nearCropBoundary_clampsEveryGeneratedLayer() {
        val core = BooleanArray(WIDTH * HEIGHT).apply { this[0] = true }
        val base = BooleanArray(WIDTH * HEIGHT).apply {
            for (y in 0..3) for (x in 0..3) this[y * WIDTH + x] = true
        }
        val plan = TextRepairFeathering.plan(
            width = WIDTH,
            height = HEIGHT,
            baseMask = base,
            coreMask = core,
            coordinateScale = 1f,
        )

        assertEquals(WIDTH * HEIGHT, plan.alpha.size)
        assertEquals(WIDTH * HEIGHT, plan.opaqueMask.size)
        assertEquals(WIDTH * HEIGHT, plan.repairMask.size)
        assertEquals(255, plan.alpha[0])
        assertTrue(plan.repairPixelCount < WIDTH * HEIGHT)
    }

    @Test
    fun applyAlpha_tableDriven_preservesColorAndCombinesOpacity() {
        data class Case(
            val name: String,
            val sourceAlpha: Int,
            val maskAlpha: Int,
            val expectedAlpha: Int,
        )
        val cases = listOf(
            Case("opaque repaired pixel", sourceAlpha = 255, maskAlpha = 255, expectedAlpha = 255),
            Case("feathered repaired pixel", sourceAlpha = 255, maskAlpha = 128, expectedAlpha = 128),
            Case("source transparency is retained", sourceAlpha = 128, maskAlpha = 128, expectedAlpha = 64),
            Case("outside repair", sourceAlpha = 255, maskAlpha = 0, expectedAlpha = 0),
        )

        cases.forEach { case ->
            val color = (case.sourceAlpha shl 24) or 0x00123456
            val output = TextRepairFeathering.applyAlpha(color, case.maskAlpha)
            assertEquals(case.name, case.expectedAlpha, output ushr 24)
            if (case.expectedAlpha == 0) {
                assertEquals(case.name, 0, output)
            } else {
                assertEquals("${case.name}: RGB is preserved", 0x00123456, output and 0x00ffffff)
            }
        }
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
