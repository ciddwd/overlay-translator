package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextBackgroundRepairerTest {

    @Test
    fun repairTiming_tableDriven_accountsForEveryPipelineShape() {
        data class Case(
            val name: String,
            val masks: List<TextPixelMaskBuilder.BlockMask>,
        )

        val erase = centeredMask()
        val cases = listOf(
            Case("no text masks", emptyList()),
            Case("single text mask", listOf(blockMask(erase))),
            Case("multiple text masks", listOf(blockMask(erase), blockMask(erase))),
        )

        cases.forEach { case ->
            val source = IntArray(SIZE) { gray(248) }.apply {
                erase.indices.filter { erase[it] }.forEach { this[it] = gray(8) }
            }
            val result = LocalTextBackgroundRepairer.repair(
                imageWidth = WIDTH,
                imageHeight = HEIGHT,
                sourceArgb = source,
                masks = case.masks,
            )
            val timing = result.timing

            assertEquals(case.name, case.masks.size, result.blocks.size)
            assertTrue(case.name, timing.totalUs >= 0L)
            assertTrue(case.name, timing.measuredStageUs >= 0L)
            assertTrue(case.name, timing.otherUs >= 0L)
            assertEquals(case.name, timing.totalUs, timing.measuredStageUs + timing.otherUs)
            assertTrue(case.name, timing.toLogString().startsWith("total="))
        }
    }

    @Test
    fun repair_tableDriven_createsGlyphOnlyPatchesForFlatBackgrounds() {
        data class Case(val name: String, val background: Int, val foreground: Int)
        val cases = listOf(
            Case("light bubble", argb(255, 248, 248, 248), argb(255, 8, 8, 8)),
            Case("dark bubble", argb(255, 24, 24, 27), argb(255, 244, 244, 245)),
            Case("colored bubble", argb(255, 186, 230, 253), argb(255, 30, 64, 175)),
        )

        cases.forEach { case ->
            val erase = centeredMask()
            val source = IntArray(SIZE) { case.background }
            erase.indices.filter { erase[it] }.forEach { source[it] = case.foreground }
            val result = LocalTextBackgroundRepairer.repair(
                imageWidth = WIDTH,
                imageHeight = HEIGHT,
                sourceArgb = source,
                masks = listOf(blockMask(erase)),
            )

            val block = result.blocks.single()
            val patch = requireNotNull(block.patchPixels)
            assertTrue(case.name, block.fullyRepaired)
            assertEquals(case.name, 1, block.acceptedComponentCount)
            patch.indices.forEach { index ->
                when {
                    block.coverage.repairMask[index] -> {
                        assertEquals("${case.name}: solid coverage is opaque", 255, patch[index] ushr 24)
                        assertEquals(
                            "${case.name}: hard coverage uses repaired background",
                            case.background and 0x00ffffff,
                            patch[index] and 0x00ffffff,
                        )
                    }
                    else -> assertEquals("${case.name}: pixels outside repair stay transparent", 0, patch[index])
                }
            }
        }
    }

    @Test
    fun repair_tableDriven_coverageFirstRepairsComplexBackgroundsWithoutMutatingInput() {
        data class Case(
            val name: String,
            val foreground: Int,
            val expectedLightBackground: Boolean,
        )
        val cases = listOf(
            Case(
                name = "dark text excludes black line-art samples",
                foreground = argb(255, 8, 8, 8),
                expectedLightBackground = true,
            ),
            Case(
                name = "light text excludes white highlight samples",
                foreground = argb(255, 248, 248, 248),
                expectedLightBackground = false,
            ),
        )
        val erase = centeredMask()
        val core = BooleanArray(SIZE).apply {
            fill(this, IntRect(15, 15, 18, 18))
        }

        cases.forEach { case ->
            val source = IntArray(SIZE) { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                if ((x + y) % 2 == 0) argb(255, 12, 12, 12) else argb(255, 242, 242, 242)
            }
            core.indices.filter { core[it] }.forEach { source[it] = case.foreground }
            val before = source.copyOf()
            val result = LocalTextBackgroundRepairer.repair(
                imageWidth = WIDTH,
                imageHeight = HEIGHT,
                sourceArgb = source,
                masks = listOf(blockMask(erase, core)),
            )

            val block = result.blocks.single()
            assertTrue(case.name, block.fullyRepaired)
            val patch = requireNotNull(block.patchPixels)
            erase.indices.filter { erase[it] }.forEach { index ->
                assertTrue("${case.name}: every glyph pixel is painted", patch[index] != 0)
            }
            block.coverage.repairMask.indices.filter { block.coverage.repairMask[it] }.forEach { index ->
                assertEquals("${case.name}: coverage-first area stays opaque", 255, patch[index] ushr 24)
            }
            block.coverage.repairMask.indices.filterNot { block.coverage.repairMask[it] }.forEach { index ->
                assertEquals("${case.name}: pixels outside repair stay transparent", 0, patch[index])
            }
            assertTrue("${case.name}: no partial alpha", patch.none { color -> color ushr 24 in 1..254 })
            core.indices.filter { core[it] }.forEach { index ->
                val luminance = patch[index] and 0xff
                if (case.expectedLightBackground) {
                    assertTrue("${case.name}: dark ink is not painted back", luminance >= 200)
                } else {
                    assertTrue("${case.name}: light ink is not painted back", luminance <= 48)
                }
            }
            assertTrue("${case.name}: source is immutable", before.contentEquals(source))
            assertTrue(case.name, LocalTextBackgroundRepairer.rejectionDiagnostics(result).isEmpty())
        }
    }

    @Test
    fun repair_complexAndFlatComponents_publishesEveryGlyphPixel() {
        val white = argb(255, 250, 250, 250)
        val source = IntArray(SIZE) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            if (x < WIDTH / 2) white else if ((x + y) % 2 == 0) {
                argb(255, 10, 10, 10)
            } else {
                argb(255, 245, 245, 245)
            }
        }
        val erase = BooleanArray(SIZE)
        fill(erase, IntRect(6, 14, 10, 18))
        fill(erase, IntRect(22, 14, 26, 18))
        erase.indices.filter { erase[it] }.forEach {
            source[it] = argb(255, 8, 8, 8)
        }

        val block = LocalTextBackgroundRepairer.repair(
            imageWidth = WIDTH,
            imageHeight = HEIGHT,
            sourceArgb = source,
            masks = listOf(blockMask(erase)),
        ).blocks.single()

        assertEquals(block.componentCount, block.acceptedComponentCount)
        assertTrue(block.fullyRepaired)
        assertTrue(block.publishable)
        val patch = requireNotNull(block.patchPixels)
        assertTrue("accepted flat component is published", patch[16 * WIDTH + 8] != 0)
        assertTrue("complex component is also published", patch[16 * WIDTH + 24] != 0)
        assertTrue("dark glyph is not restored on complex light background", patch[16 * WIDTH + 24] and 0xff >= 200)
        assertTrue(
            LocalTextBackgroundRepairer.rejectionDiagnostics(
                LocalTextBackgroundRepairer.Result(listOf(block)),
            ).isEmpty(),
        )
    }

    @Test
    fun repair_tableDriven_marksSamePolarityPixelsOutsideCoverageAsResidual() {
        data class Case(
            val name: String,
            val background: Int,
            val foreground: Int,
            val missedInsideSupport: Boolean,
            val expectedResidual: Boolean,
        )
        listOf(
            Case("dark corner on light bubble", gray(248), gray(8), true, true),
            Case("light corner on dark panel", gray(18), gray(245), true, true),
            Case("foreground outside OCR support", gray(248), gray(8), false, false),
        ).forEach { case ->
            val erase = centeredMask()
            val support = BooleanArray(SIZE).apply { fill(this, IntRect(8, 8, 25, 25)) }
            val source = IntArray(SIZE) { case.background }
            erase.indices.filter { erase[it] }.forEach { source[it] = case.foreground }
            val missedX = if (case.missedInsideSupport) 22 else 27
            source[16 * WIDTH + missedX] = case.foreground

            val block = LocalTextBackgroundRepairer.repair(
                imageWidth = WIDTH,
                imageHeight = HEIGHT,
                sourceArgb = source,
                masks = listOf(blockMask(erase, erase, support)),
            ).blocks.single()

            assertEquals(case.name, case.expectedResidual, block.residualPixels > 0)
            assertEquals(case.name, !case.expectedResidual, block.fullyRepaired)
            assertTrue("${case.name}: diagnostics do not suppress a valid patch", block.publishable)
        }
    }

    private fun blockMask(
        mask: BooleanArray,
        core: BooleanArray = mask,
        support: BooleanArray = mask,
    ) = TextPixelMaskBuilder.BlockMask(
        blockIndex = 0,
        bounds = IntRect(0, 0, WIDTH, HEIGHT),
        pixels = mask,
        corePixels = core,
        selectedCorePixels = core.count { it },
        supportPixels = support,
    )

    private fun centeredMask() = BooleanArray(SIZE).apply {
        fill(this, IntRect(13, 13, 20, 20))
    }

    private fun fill(mask: BooleanArray, bounds: IntRect) {
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) mask[y * WIDTH + x] = true
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun gray(value: Int): Int = argb(255, value, value, value)

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 32
        const val SIZE = WIDTH * HEIGHT
    }
}
