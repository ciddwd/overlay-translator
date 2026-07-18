package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicalDisplaySizeTest {

    @Test
    fun selectPhysicalDisplaySize_tableDriven_prefersOneFullDisplayCoordinateSpace() {
        data class Case(
            val name: String,
            val real: DisplayPixelSize,
            val maximum: DisplayPixelSize,
            val resources: DisplayPixelSize,
            val expected: DisplayPixelSize,
        )

        val cases = listOf(
            Case(
                name = "HyperOS landscape keeps physical width instead of inset workspace",
                real = DisplayPixelSize(3200, 1440),
                maximum = DisplayPixelSize(3200, 1440),
                resources = DisplayPixelSize(3053, 1440),
                expected = DisplayPixelSize(3200, 1440),
            ),
            Case(
                name = "portrait real metrics win",
                real = DisplayPixelSize(1440, 3200),
                maximum = DisplayPixelSize(1440, 3053),
                resources = DisplayPixelSize(1440, 3053),
                expected = DisplayPixelSize(1440, 3200),
            ),
            Case(
                name = "missing display falls back to maximum window",
                real = DisplayPixelSize(0, 0),
                maximum = DisplayPixelSize(2560, 1600),
                resources = DisplayPixelSize(2400, 1600),
                expected = DisplayPixelSize(2560, 1600),
            ),
            Case(
                name = "invalid platform metrics fall back to resources",
                real = DisplayPixelSize(-1, 1440),
                maximum = DisplayPixelSize(0, 0),
                resources = DisplayPixelSize(1080, 2400),
                expected = DisplayPixelSize(1080, 2400),
            ),
            Case(
                name = "all invalid still yields drawable coordinate space",
                real = DisplayPixelSize(0, 0),
                maximum = DisplayPixelSize(-1, -1),
                resources = DisplayPixelSize(0, 100),
                expected = DisplayPixelSize(1, 1),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                selectPhysicalDisplaySize(case.real, case.maximum, case.resources),
            )
        }
    }
}
