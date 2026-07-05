package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class ArcMenuGeometryTest {

    @Test
    fun spreadFor_usesWiderHalfCircleForSixButtonPages() {
        val cases = listOf(
            1 to 0.0,
            2 to 30.0,
            3 to 45.0,
            4 to 54.0,
            5 to 72.0,
            6 to 90.0
        )

        cases.forEach { (itemCount, expectedDegrees) ->
            assertEquals(
                "itemCount=$itemCount",
                expectedDegrees,
                Math.toDegrees(ArcMenuGeometry.spreadFor(itemCount)),
                0.0001
            )
        }
    }
}
