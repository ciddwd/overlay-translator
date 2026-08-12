package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskedBackgroundRepairerTest {

    @Test
    fun repair_tableDriven_reusedCompletionLabelsMatchIndependentLabelingPixelForPixel() {
        data class Case(
            val name: String,
            val source: IntArray,
            val allowDirectional: Boolean,
            val allowComplex: Boolean,
            val useForegroundReference: Boolean,
        )

        val erase = centeredEraseMask()
        val flat = IntArray(SIZE) { argb(255, 248, 248, 248) }.apply {
            erase.indices.filter { erase[it] }.forEach { this[it] = argb(255, 8, 8, 8) }
        }
        val gradient = IntArray(SIZE) { index ->
            val level = 72 + (index % WIDTH) * 4
            argb(255, level, level, level)
        }.apply {
            erase.indices.filter { erase[it] }.forEach { this[it] = argb(255, 8, 8, 8) }
        }
        val complex = IntArray(SIZE) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            if ((x + y) % 2 == 0) argb(255, 12, 12, 12) else argb(255, 242, 242, 242)
        }.apply {
            erase.indices.filter { erase[it] }.forEach { this[it] = argb(255, 8, 8, 8) }
        }

        listOf(
            Case("dominant fill", flat, false, false, false),
            Case("directional interpolation", gradient, true, false, false),
            Case("spatial interpolation", complex, true, true, true),
        ).forEach { case ->
            fun repair(flatCompletionMask: BooleanArray) = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = case.source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
                flatCompletionMask = flatCompletionMask,
                allowDirectionalInterpolation = case.allowDirectional,
                allowComplexBackgroundInterpolation = case.allowComplex,
                foregroundReferenceMask = erase.takeIf { case.useForegroundReference },
            )

            val reused = repair(erase)
            val independentlyLabeled = repair(erase.copyOf())

            assertTrue(case.name, reused.pixels.contentEquals(independentlyLabeled.pixels))
            assertTrue(case.name, reused.repairedMask.contentEquals(independentlyLabeled.repairedMask))
            assertEquals(case.name, reused.decisions, independentlyLabeled.decisions)
        }
    }

    @Test
    fun repair_tableDriven_restoresFlatLightDarkAndColoredBackgrounds() {
        data class Case(
            val name: String,
            val background: Int,
            val foreground: Int,
        )
        val cases = listOf(
            Case("white bubble with black text", argb(255, 248, 248, 248), argb(255, 8, 8, 8)),
            Case("dark bubble with light text", argb(255, 24, 24, 27), argb(255, 244, 244, 245)),
            Case("colored bubble", argb(255, 186, 230, 253), argb(255, 30, 64, 175)),
        )

        cases.forEach { case ->
            val source = IntArray(SIZE) { case.background }
            val erase = centeredEraseMask().also { mask ->
                mask.indices.filter { mask[it] }.forEach { source[it] = case.foreground }
            }
            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
            )

            assertEquals(case.name, 1, result.acceptedComponentCount)
            assertEquals(case.name, 0, result.rejectedComponentCount)
            assertEquals(
                "${case.name}: flat bubble uses dominant fill",
                MaskedBackgroundRepairer.Mode.DOMINANT_FILL,
                result.decisions.single().mode,
            )
            erase.indices.filter { erase[it] }.forEach { index ->
                assertEquals(case.name, case.background, result.pixels[index])
                assertTrue("${case.name}: erase pixel is marked repaired", result.repairedMask[index])
            }
        }
    }

    @Test
    fun repair_gradientBackground_keepsLegacyStrictFallbackUnlessCapabilityIsEnabled() {
        val source = IntArray(SIZE) { index ->
            val level = 80 + (index % WIDTH) * 3
            argb(255, level, level, level)
        }
        val erase = centeredEraseMask()
        erase.indices.filter { erase[it] }.forEach { source[it] = argb(255, 0, 0, 0) }
        val before = source.copyOf()

        val result = MaskedBackgroundRepairer.repair(
            width = WIDTH,
            height = HEIGHT,
            sourceArgb = source,
            eraseMask = erase,
            allowedSampleMask = BooleanArray(SIZE) { true },
        )

        assertEquals(0, result.acceptedComponentCount)
        assertEquals(MaskedBackgroundRepairer.Reason.BACKGROUND_NOT_FLAT, result.decisions.single().reason)
        assertTrue(before.contentEquals(result.pixels))
        assertFalse(result.repairedMask.any { it })
    }

    @Test
    fun repair_tableDriven_rejectsUnsafeBackgroundsWithoutChangingSource() {
        data class Case(
            val name: String,
            val source: IntArray,
            val allowed: BooleanArray,
            val expectedReason: MaskedBackgroundRepairer.Reason,
        )
        val erase = centeredEraseMask()
        val checkerboard = IntArray(SIZE) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            if ((x + y) % 2 == 0) argb(255, 10, 10, 10) else argb(255, 245, 245, 245)
        }
        val cases = listOf(
            Case(
                name = "checkerboard background is too complex",
                source = checkerboard,
                allowed = BooleanArray(SIZE) { true },
                expectedReason = MaskedBackgroundRepairer.Reason.BACKGROUND_TOO_COMPLEX,
            ),
            Case(
                name = "model bubble provides no clean boundary samples",
                source = IntArray(SIZE) { argb(255, 250, 250, 250) },
                allowed = erase.copyOf(),
                expectedReason = MaskedBackgroundRepairer.Reason.INSUFFICIENT_BOUNDARY_SAMPLES,
            ),
        )

        cases.forEach { case ->
            val before = case.source.copyOf()
            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = case.source,
                eraseMask = erase,
                allowedSampleMask = case.allowed,
            )

            assertEquals(case.name, case.expectedReason, result.decisions.single().reason)
            assertEquals(case.name, 0, result.acceptedComponentCount)
            assertTrue("${case.name}: rejected pixels are unchanged", before.contentEquals(result.pixels))
            assertFalse("${case.name}: rejected pixels are not marked repaired", result.repairedMask.any { it })
        }
    }

    @Test
    fun repair_tableDriven_usesOnlyAllowedLocalSamplesAndPreservesOutsideMask() {
        data class Case(
            val name: String,
            val insideColor: Int,
            val outsideColor: Int,
        )
        val cases = listOf(
            Case(
                "red panel outside white bubble cannot contaminate repair",
                argb(255, 250, 250, 250),
                argb(255, 220, 38, 38),
            ),
            Case(
                "white panel outside dark bubble cannot contaminate repair",
                argb(255, 24, 24, 27),
                argb(255, 250, 250, 250),
            ),
        )

        cases.forEach { case ->
            val allowed = BooleanArray(SIZE)
            val source = IntArray(SIZE) { case.outsideColor }
            for (y in 5 until HEIGHT - 5) {
                for (x in 5 until WIDTH - 5) {
                    val index = y * WIDTH + x
                    allowed[index] = true
                    source[index] = case.insideColor
                }
            }
            val erase = centeredEraseMask()
            erase.indices.filter { erase[it] }.forEach { source[it] = case.outsideColor }
            val before = source.copyOf()
            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = allowed,
            )

            assertEquals(case.name, 1, result.acceptedComponentCount)
            erase.indices.filter { erase[it] }.forEach { index ->
                assertEquals(case.name, case.insideColor, result.pixels[index])
            }
            erase.indices.filterNot { erase[it] }.forEach { index ->
                assertEquals("${case.name}: clean pixels stay untouched", before[index], result.pixels[index])
            }
        }
    }

    @Test
    fun repair_tableDriven_usesDirectionalInterpolationForCoherentGradients() {
        data class Case(
            val name: String,
            val levelAt: (x: Int, y: Int) -> Int,
        )
        val cases = listOf(
            Case("horizontal gradient") { x, _ -> 80 + x * 3 },
            Case("vertical gradient") { _, y -> 80 + y * 3 },
        )

        cases.forEach { case ->
            val expected = IntArray(SIZE) { index ->
                val level = case.levelAt(index % WIDTH, index / WIDTH)
                argb(255, level, level, level)
            }
            val source = expected.copyOf()
            val erase = centeredEraseMask()
            erase.indices.filter { erase[it] }.forEach { source[it] = argb(255, 0, 0, 0) }
            val before = source.copyOf()
            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
                allowDirectionalInterpolation = true,
            )

            assertEquals(case.name, 1, result.acceptedComponentCount)
            assertEquals(
                case.name,
                MaskedBackgroundRepairer.Mode.DIRECTIONAL_INTERPOLATION,
                result.decisions.single().mode,
            )
            erase.indices.filter { erase[it] }.forEach { index ->
                val expectedLevel = expected[index] and 0xff
                val actualLevel = result.pixels[index] and 0xff
                assertTrue(case.name, kotlin.math.abs(expectedLevel - actualLevel) <= 12)
                assertTrue(case.name, result.repairedMask[index])
            }
            erase.indices.filterNot { erase[it] }.forEach { index ->
                assertEquals("${case.name}: outside mask is immutable", before[index], result.pixels[index])
            }
        }
    }

    @Test
    fun repair_tableDriven_complexBackgroundInterpolationRequiresExplicitCapability() {
        data class Case(
            val name: String,
            val foreground: Int,
            val expectedLightBackground: Boolean,
        )
        val cases = listOf(
            Case(
                name = "dark glyph rejects black line-art contamination",
                foreground = argb(255, 8, 8, 8),
                expectedLightBackground = true,
            ),
            Case(
                name = "light glyph rejects white highlight contamination",
                foreground = argb(255, 248, 248, 248),
                expectedLightBackground = false,
            ),
        )

        cases.forEach { case ->
            val erase = centeredEraseMask()
            val source = IntArray(SIZE) { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                if ((x + y) % 2 == 0) argb(255, 12, 12, 12) else argb(255, 242, 242, 242)
            }
            val foregroundCore = BooleanArray(SIZE)
            for (y in HEIGHT / 2 - 1..HEIGHT / 2 + 1) {
                for (x in WIDTH / 2 - 1..WIDTH / 2 + 1) {
                    val index = y * WIDTH + x
                    foregroundCore[index] = true
                    source[index] = case.foreground
                }
            }
            val before = source.copyOf()

            val strict = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
                allowDirectionalInterpolation = true,
                foregroundReferenceMask = foregroundCore,
            )
            val coverageFirst = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = foregroundCore,
            )

            assertEquals(case.name, 0, strict.acceptedComponentCount)
            assertEquals(
                case.name,
                MaskedBackgroundRepairer.Reason.BACKGROUND_TOO_COMPLEX,
                strict.decisions.single().reason,
            )
            assertEquals(case.name, 1, coverageFirst.acceptedComponentCount)
            assertEquals(
                case.name,
                MaskedBackgroundRepairer.Mode.SPATIAL_INTERPOLATION,
                coverageFirst.decisions.single().mode,
            )
            erase.indices.filter { erase[it] }.forEach { index ->
                assertTrue("${case.name}: every glyph pixel is repaired", coverageFirst.repairedMask[index])
            }
            foregroundCore.indices.filter { foregroundCore[it] }.forEach { index ->
                val luminance = coverageFirst.pixels[index] and 0xff
                if (case.expectedLightBackground) {
                    assertTrue("${case.name}: dark ink is not painted back", luminance >= 200)
                } else {
                    assertTrue("${case.name}: light ink is not painted back", luminance <= 48)
                }
            }
            erase.indices.filterNot { erase[it] }.forEach { index ->
                assertEquals("${case.name}: outside mask is immutable", before[index], coverageFirst.pixels[index])
            }
        }
    }

    @Test
    fun repair_tableDriven_spatialInterpolationDoesNotSpreadForegroundExtremesAcrossTexture() {
        data class Case(
            val name: String,
            val foreground: Int,
        )
        listOf(
            Case("black text beside black line art", argb(255, 8, 8, 8)),
            Case("white text beside white highlight", argb(255, 248, 248, 248)),
        ).forEach { case ->
            val expectedBackground = IntArray(SIZE) { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                val level = if ((x + y) % 2 == 0) 112 else 176
                argb(255, level, level, level)
            }
            val source = expectedBackground.copyOf()
            val erase = centeredEraseMask()
            erase.indices.filter { erase[it] }.forEach { source[it] = case.foreground }
            val foregroundCore = BooleanArray(SIZE)
            for (y in HEIGHT / 2 - 1..HEIGHT / 2 + 1) {
                for (x in WIDTH / 2 - 1..WIDTH / 2 + 1) {
                    foregroundCore[y * WIDTH + x] = true
                }
            }
            val contaminatingLineX = WIDTH / 2 + 4
            for (y in 5 until HEIGHT - 5) {
                source[y * WIDTH + contaminatingLineX] = case.foreground
            }
            val before = source.copyOf()

            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = foregroundCore,
            )

            val decision = result.decisions.single()
            assertEquals(case.name, MaskedBackgroundRepairer.Mode.SPATIAL_INTERPOLATION, decision.mode)
            assertTrue("${case.name}: reference color is logged", decision.referenceColor != null)
            assertTrue("${case.name}: boundary range is logged", decision.boundaryLuminanceMax > decision.boundaryLuminanceMin)
            assertTrue("${case.name}: output range is logged", decision.outputLuminanceMax >= decision.outputLuminanceMin)
            erase.indices.filter { erase[it] }.forEach { index ->
                val luminance = result.pixels[index] and 0xff
                assertTrue("${case.name}: repair stays in textured midtones", luminance in 96..192)
                assertTrue("${case.name}: every text pixel is repaired", result.repairedMask[index])
            }
            erase.indices.filterNot { erase[it] }.forEach { index ->
                assertEquals("${case.name}: pixels outside repair are immutable", before[index], result.pixels[index])
            }
        }
    }

    @Test
    fun repair_tableDriven_spatialInterpolationClipsAtImageEdges() {
        data class Case(val name: String, val left: Int, val top: Int)
        listOf(
            Case("top left", 0, 0),
            Case("bottom right", WIDTH - 4, HEIGHT - 4),
        ).forEach { case ->
            val source = IntArray(SIZE) { index ->
                val level = if (((index % WIDTH) + (index / WIDTH)) % 2 == 0) 112 else 176
                argb(255, level, level, level)
            }
            val erase = BooleanArray(SIZE)
            val foreground = BooleanArray(SIZE)
            for (y in case.top until minOf(case.top + 4, HEIGHT)) {
                for (x in case.left until minOf(case.left + 4, WIDTH)) {
                    val index = y * WIDTH + x
                    erase[index] = true
                    foreground[index] = true
                    source[index] = argb(255, 8, 8, 8)
                }
            }

            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = BooleanArray(SIZE) { true },
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = foreground,
            )

            assertEquals(case.name, 1, result.acceptedComponentCount)
            assertEquals(case.name, MaskedBackgroundRepairer.Mode.SPATIAL_INTERPOLATION, result.decisions.single().mode)
            erase.indices.filter { erase[it] }.forEach { index ->
                assertTrue("${case.name}: edge pixel is repaired", result.repairedMask[index])
            }
        }
    }

    @Test
    fun repair_flatBackground_dominantFillDoesNotReintroduceAntialiasHalo() {
        val background = argb(255, 250, 250, 250)
        val source = IntArray(SIZE) { background }
        val erase = centeredEraseMask()
        erase.indices.filter { erase[it] }.forEach { source[it] = argb(255, 16, 16, 16) }
        for (y in HEIGHT / 2 - 5..HEIGHT / 2 + 5) {
            for (x in WIDTH / 2 - 5..WIDTH / 2 + 5) {
                val index = y * WIDTH + x
                if (!erase[index] && (x + y) % 3 == 0) {
                    source[index] = argb(255, 224, 224, 224)
                }
            }
        }

        val result = MaskedBackgroundRepairer.repair(
            width = WIDTH,
            height = HEIGHT,
            sourceArgb = source,
            eraseMask = erase,
            allowedSampleMask = BooleanArray(SIZE) { true },
        )

        assertEquals(1, result.acceptedComponentCount)
        assertEquals(
            MaskedBackgroundRepairer.Mode.DOMINANT_FILL,
            result.decisions.single().mode,
        )
        erase.indices.filter { erase[it] }.forEach { index ->
            assertEquals("halo samples must not create gray ghosts", background, result.pixels[index])
        }
    }

    @Test
    fun repair_tableDriven_flatCompletionRemovesDisconnectedResidueButStaysInsideAllowedMask() {
        data class Case(
            val name: String,
            val allowedRight: Int,
            val expectedRightRepaired: Boolean,
        )
        val cases = listOf(
            Case(
                name = "entire OCR completion box is inside bubble",
                allowedRight = WIDTH,
                expectedRightRepaired = true,
            ),
            Case(
                name = "completion box is clipped by model bubble",
                allowedRight = WIDTH / 2 + 2,
                expectedRightRepaired = false,
            ),
        )
        cases.forEach { case ->
            val background = argb(255, 250, 250, 250)
            val foreground = argb(255, 12, 12, 12)
            val source = IntArray(SIZE) { background }
            val erase = BooleanArray(SIZE).apply {
                for (y in HEIGHT / 2 - 1..HEIGHT / 2 + 1) {
                    for (x in WIDTH / 2 - 1..WIDTH / 2 + 1) this[y * WIDTH + x] = true
                }
            }
            val completion = BooleanArray(SIZE).apply {
                for (y in HEIGHT / 2 - 4..HEIGHT / 2 + 4) {
                    for (x in WIDTH / 2 - 5..WIDTH / 2 + 5) this[y * WIDTH + x] = true
                }
            }
            val allowed = BooleanArray(SIZE).apply {
                for (y in 0 until HEIGHT) {
                    for (x in 0 until case.allowedRight) this[y * WIDTH + x] = true
                }
            }
            erase.indices.filter { erase[it] }.forEach { source[it] = foreground }
            val leftResidue = (HEIGHT / 2) * WIDTH + WIDTH / 2 - 4
            val rightResidue = (HEIGHT / 2) * WIDTH + WIDTH / 2 + 4
            source[leftResidue] = foreground
            source[rightResidue] = foreground

            val result = MaskedBackgroundRepairer.repair(
                width = WIDTH,
                height = HEIGHT,
                sourceArgb = source,
                eraseMask = erase,
                allowedSampleMask = allowed,
                flatCompletionMask = completion,
            )

            assertEquals(case.name, 1, result.acceptedComponentCount)
            assertEquals("${case.name}: residue inside bubble is removed", background, result.pixels[leftResidue])
            assertEquals(
                "${case.name}: completion respects model bubble",
                case.expectedRightRepaired,
                result.pixels[rightResidue] == background,
            )
            assertEquals(
                "${case.name}: repaired mask respects model bubble",
                case.expectedRightRepaired,
                result.repairedMask[rightResidue],
            )
        }
    }

    private fun centeredEraseMask(): BooleanArray =
        BooleanArray(SIZE).apply {
            for (y in HEIGHT / 2 - 3..HEIGHT / 2 + 3) {
                for (x in WIDTH / 2 - 3..WIDTH / 2 + 3) {
                    this[y * WIDTH + x] = true
                }
            }
        }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 32
        const val SIZE = WIDTH * HEIGHT
    }
}
