package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRegionOriginPolicyTest {
    @Test
    fun captureRegionOrigin_mapsLocalOcrCoordinatesBackToTheScreen() {
        data class Case(
            val name: String,
            val region: CaptureRegion?,
            val expected: CaptureRegionOrigin,
        )

        listOf(
            Case("full screen", null, CaptureRegionOrigin(0, 0)),
            Case("valid top-left region", CaptureRegion(0, 0, 800, 600), CaptureRegionOrigin(0, 0)),
            Case(
                "valid lower-right region",
                CaptureRegion(587, 1853, 1406, 2517),
                CaptureRegionOrigin(587, 1853),
            ),
            Case("invalid narrow region uses full screen", CaptureRegion(20, 30, 28, 200), CaptureRegionOrigin(0, 0)),
            Case("invalid short region uses full screen", CaptureRegion(20, 30, 200, 38), CaptureRegionOrigin(0, 0)),
        ).forEach { case ->
            assertEquals(case.name, case.expected, captureRegionOrigin(case.region))
        }
    }
}
