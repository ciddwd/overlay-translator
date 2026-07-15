package com.gameocr.app.capture

import com.gameocr.app.data.LoopTextRegionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopTextRoiPolicyTest {
    @Test
    fun select_usesDialogueCandidateAndKeepsRoomForStreamingText() {
        data class Case(
            val name: String,
            val candidates: List<LoopTextRoiCandidate>,
            val width: Int,
            val height: Int,
            val expected: LoopTextRect?,
        )

        val cases = listOf(
            Case("empty OCR", emptyList(), 1000, 500, null),
            Case(
                name = "long dialogue beats short interface labels",
                candidates = listOf(
                    candidate("SKIP", 850, 20, 930, 50),
                    candidate("A Dark Forest", 20, 20, 180, 50),
                    candidate("You're in for one painful ride from here on out, Dante.", 300, 300, 700, 350),
                ),
                width = 1000,
                height = 500,
                expected = LoopTextRect(200, 250, 800, 412),
            ),
            Case(
                name = "nearby speaker line joins dialogue ROI",
                candidates = listOf(
                    candidate("Dante", 300, 220, 390, 250),
                    candidate("This line continues while the dialogue is being typed.", 280, 270, 720, 320),
                    candidate("MENU", 10, 10, 80, 40),
                ),
                width = 1000,
                height = 500,
                expected = LoopTextRect(170, 120, 830, 420),
            ),
            Case(
                name = "edge ROI is clamped to image",
                candidates = listOf(candidate("A sufficiently long subtitle", 0, 450, 300, 495)),
                width = 1000,
                height = 500,
                expected = LoopTextRect(0, 343, 550, 500),
            ),
            Case(
                name = "partial lower dialogue beats longer static title",
                candidates = listOf(
                    candidate("Aboard Mephistopheles", 10, 20, 300, 70),
                    candidate("It", 400, 400, 500, 450),
                    candidate("SKIP", 850, 20, 930, 50),
                ),
                width = 1000,
                height = 500,
                expected = LoopTextRect(175, 338, 725, 500),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopTextRoiPolicy.select(case.candidates, case.width, case.height),
            )
        }
    }

    @Test
    fun select_rejectsInvalidImagesAndTinyNoise() {
        assertNull(LoopTextRoiPolicy.select(listOf(candidate("dialogue", 0, 0, 10, 10)), 0, 100))
        assertNull(LoopTextRoiPolicy.select(listOf(candidate("?", 0, 0, 10, 10)), 100, 100))
        assertNull(LoopTextRoiPolicy.select(listOf(candidate("dialogue", 10, 10, 10, 20)), 100, 100))
    }

    @Test
    fun selectedRoi_alwaysContainsPrimaryText() {
        val primary = candidate("The complete dialogue sentence belongs inside the ROI.", 420, 180, 760, 240)
        val roi = LoopTextRoiPolicy.select(listOf(primary), 1280, 720)!!

        assertTrue(roi.left <= primary.rect.left)
        assertTrue(roi.top <= primary.rect.top)
        assertTrue(roi.right >= primary.rect.right)
        assertTrue(roi.bottom >= primary.rect.bottom)
    }

    @Test
    fun containsCenter_filtersStaticTextOutsideDialogueRoi() {
        val roi = LoopTextRect(0, 700, 1000, 1000)
        data class Case(val name: String, val rect: LoopTextRect, val expected: Boolean)
        listOf(
            Case("dialogue inside", LoopTextRect(200, 800, 700, 850), true),
            Case("speaker name inside", LoopTextRect(100, 710, 250, 750), true),
            Case("top title outside", LoopTextRect(10, 20, 400, 80), false),
            Case("skip button outside", LoopTextRect(900, 100, 990, 140), false),
            Case("overlap without center stays outside", LoopTextRect(100, 650, 200, 710), false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, LoopTextRoiPolicy.containsCenter(roi, case.rect))
        }
    }

    @Test
    fun regionMode_changesPrimarySelectionWithoutChangingBoundsSafety() {
        val candidates = listOf(
            candidate("Aboard Mephistopheles", 10, 20, 300, 70),
            candidate("It", 400, 400, 500, 450),
            candidate("SKIP", 850, 20, 930, 50),
        )
        data class Case(
            val name: String,
            val mode: LoopTextRegionMode,
            val expected: LoopTextRect,
        )
        listOf(
            Case("auto balances position and text", LoopTextRegionMode.AUTO, LoopTextRect(175, 338, 725, 500)),
            Case("lower screen explicitly prefers dialogue", LoopTextRegionMode.LOWER_SCREEN_FIRST, LoopTextRect(175, 338, 725, 500)),
            Case("anywhere allows the longest title", LoopTextRegionMode.ANYWHERE, LoopTextRect(0, 0, 550, 162)),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopTextRoiPolicy.select(candidates, 1000, 500, case.mode),
            )
        }
    }

    @Test
    fun roiCoordinates_mapBackForNormalAndUpscaledOcr() {
        data class Case(
            val name: String,
            val block: LoopTextRect,
            val roi: LoopTextRect,
            val upscale2x: Boolean,
            val expected: LoopTextRect,
        )
        listOf(
            Case(
                "normal coordinates add ROI origin",
                LoopTextRect(10, 20, 110, 70),
                LoopTextRect(300, 200, 900, 500),
                false,
                LoopTextRect(310, 220, 410, 270),
            ),
            Case(
                "upscaled coordinates divide before adding origin",
                LoopTextRect(20, 40, 220, 140),
                LoopTextRect(300, 200, 900, 500),
                true,
                LoopTextRect(310, 220, 410, 270),
            ),
            Case(
                "odd upscaled coordinates use deterministic integer mapping",
                LoopTextRect(1, 3, 201, 103),
                LoopTextRect(50, 70, 600, 300),
                true,
                LoopTextRect(50, 71, 150, 121),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopRoiCoordinatePolicy.mapFromRoi(case.block, case.roi, case.upscale2x),
            )
        }
    }

    private fun candidate(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        LoopTextRoiCandidate(text, LoopTextRect(left, top, right, bottom))
}
