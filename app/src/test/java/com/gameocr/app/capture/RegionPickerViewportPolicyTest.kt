package com.gameocr.app.capture

import org.junit.Assert.*
import org.junit.Test

class RegionPickerViewportPolicyTest {
    @Test fun differentResolutionsAndAspectRatiosKeepTheSelectionProportional_tableDriven() {
        data class Case(val oldW: Int, val oldH: Int, val newW: Int, val newH: Int,
                        val region: CaptureRegion, val expected: CaptureRegion)
        listOf(
            Case(1080, 2400, 2400, 1080, CaptureRegion(108, 240, 972, 2160), CaptureRegion(240, 108, 2160, 972)),
            Case(1080, 2400, 720, 1600, CaptureRegion(108, 240, 972, 2160), CaptureRegion(72, 160, 648, 1440)),
            Case(720, 1600, 1440, 3200, CaptureRegion(72, 160, 648, 1440), CaptureRegion(144, 320, 1296, 2880)),
            Case(2560, 1600, 1600, 2560, CaptureRegion(256, 160, 2304, 1440), CaptureRegion(160, 256, 1440, 2304)),
            Case(1080, 2520, 2200, 1800, CaptureRegion(108, 252, 972, 2268), CaptureRegion(220, 180, 1980, 1620)),
            Case(2200, 1800, 1080, 2520, CaptureRegion(220, 180, 1980, 1620), CaptureRegion(108, 252, 972, 2268)),
            Case(1000, 2000, 981, 2055, CaptureRegion(100, 400, 900, 1600), CaptureRegion(98, 411, 882, 1644)),
        ).forEach { case ->
            val viewport = RegionPickerViewport()
            assertEquals(case.region, viewport.resize(case.region, case.oldW, case.oldH, 40))
            assertEquals(case.toString(), case.expected,
                viewport.resize(case.region, case.newW, case.newH, 40))
        }
    }

    @Test fun transientInvalidLayoutsDoNotDestroyTheLastSelectionCoordinateBasis_tableDriven() {
        for ((width, height) in listOf(0 to 0, 0 to 2400, 1080 to 0, -1 to 2400)) {
            val viewport = RegionPickerViewport()
            val initial = CaptureRegion(108, 240, 972, 2160)
            var region = viewport.resize(initial, 1080, 2400, 40)
            assertEquals(initial, region)
            region = viewport.resize(region, width, height, 40)
            assertEquals(initial, region)
            assertEquals(1080, viewport.width)
            assertEquals(2400, viewport.height)
            assertEquals(CaptureRegion(72, 160, 648, 1440), viewport.resize(region, 720, 1600, 40))
        }
    }

    @Test fun viewportTracksSizeWithNoSelectionAndResetDoesNotRestoreAnOldRect() {
        val viewport = RegionPickerViewport()
        assertNull(viewport.resize(null, 1080, 2400, 40))
        assertNull(viewport.resize(null, 2400, 1080, 40))
        assertEquals(CaptureRegion(72, 160, 648, 1440),
            viewport.resize(CaptureRegion(240, 108, 2160, 972), 720, 1600, 40))
        assertNull(viewport.resize(null, 1440, 3200, 40))
        assertNull(viewport.resize(null, 720, 1600, 40))
    }

    @Test fun selectionFollowsBothRotationDirectionsAndInitialLayout_tableDriven() {
        data class Case(val oldW: Int, val oldH: Int, val newW: Int, val newH: Int,
                        val region: CaptureRegion, val expected: CaptureRegion)
        listOf(
            Case(1000, 2000, 2000, 1000, CaptureRegion(100, 400, 900, 1600), CaptureRegion(200, 200, 1800, 800)),
            Case(2000, 1000, 1000, 2000, CaptureRegion(200, 200, 1800, 800), CaptureRegion(100, 400, 900, 1600)),
            Case(1000, 2000, 1000, 2000, CaptureRegion(100, 400, 900, 1600), CaptureRegion(100, 400, 900, 1600)),
            Case(0, 0, 1000, 2000, CaptureRegion(100, 400, 900, 1600), CaptureRegion(100, 400, 900, 1600)),
            Case(1000, 2000, 2000, 1000, CaptureRegion(0, 0, 1000, 2000), CaptureRegion(0, 0, 2000, 1000)),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected, RegionPickerViewportPolicy.resizeSelection(
                case.region, case.oldW, case.oldH, case.newW, case.newH, 40))
        }
    }

    @Test fun edgesSmallWindowsAndOversizedSavedSelectionsRemainInsideTheViewport_tableDriven() {
        val regions = listOf(CaptureRegion(-200, -100, 1200, 2400), CaptureRegion(990, 1990, 1000, 2000),
            CaptureRegion(9000, 9000, 9500, 9500), CaptureRegion(10, 20, 100, 200))
        for ((width, height) in listOf(2000 to 1000, 1000 to 2000, 20 to 30, 1 to 1)) {
            for (region in regions) {
                val result = requireNotNull(RegionPickerViewportPolicy.resizeSelection(region, 1000, 2000, width, height, 40))
                assertTrue(result.toString(), result.left >= 0 && result.top >= 0)
                assertTrue(result.toString(), result.right <= width && result.bottom <= height)
                assertTrue(result.width >= minOf(40, width))
                assertTrue(result.height >= minOf(40, height))
            }
        }
    }

    @Test fun missingSelectionAndInvalidGeometryDoNotCreateASelection_tableDriven() {
        for (region in listOf(null, CaptureRegion(10, 10, 10, 30), CaptureRegion(50, 50, 10, 10))) {
            assertNull(RegionPickerViewportPolicy.resizeSelection(region, 1000, 2000, 2000, 1000, 40))
        }
        for ((width, height) in listOf(0 to 1000, 2000 to 0, -1 to 1000)) {
            assertNull(RegionPickerViewportPolicy.resizeSelection(CaptureRegion(10, 10, 100, 100), 1000, 2000, width, height, 40))
        }
    }

    @Test fun repeatedRotationKeepsTheUncommittedSelectionAndDoesNotMutateTheInput() {
        val original = CaptureRegion(100, 400, 900, 1600)
        var current = original
        repeat(4) {
            current = requireNotNull(RegionPickerViewportPolicy.resizeSelection(current, 1000, 2000, 2000, 1000, 40))
            current = requireNotNull(RegionPickerViewportPolicy.resizeSelection(current, 2000, 1000, 1000, 2000, 40))
        }
        assertEquals(original, current)
        assertEquals(CaptureRegion(100, 400, 900, 1600), original)
    }
}
