package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGeometryDiagnosticsTest {

    @Test
    fun diagnoseCaptureGeometry_tableDriven_classifiesCoordinateSpaceRelations() {
        data class Case(
            val name: String,
            val frameWidth: Int,
            val frameHeight: Int,
            val overlayWidth: Int,
            val overlayHeight: Int,
            val expected: CaptureCoordinateRelation
        )

        val cases = listOf(
            Case("portrait exact", 1440, 3200, 1440, 3200, CaptureCoordinateRelation.MATCH),
            Case("landscape exact", 3200, 1440, 3200, 1440, CaptureCoordinateRelation.MATCH),
            Case(
                "stale portrait projection on landscape display",
                1440,
                3200,
                3200,
                1440,
                CaptureCoordinateRelation.ORIENTATION_MISMATCH
            ),
            Case(
                "stale landscape projection on portrait display",
                3200,
                1440,
                1440,
                3200,
                CaptureCoordinateRelation.ORIENTATION_MISMATCH
            ),
            Case("uniform half scale", 3200, 1440, 1600, 720, CaptureCoordinateRelation.SCALED_SAME_ASPECT),
            Case(
                "same orientation but system inset changes aspect",
                3200,
                1440,
                3053,
                1440,
                CaptureCoordinateRelation.ASPECT_RATIO_MISMATCH
            ),
            Case("invalid zero frame", 0, 1440, 3200, 1440, CaptureCoordinateRelation.INVALID),
            Case("invalid zero overlay", 1440, 3200, 0, 0, CaptureCoordinateRelation.INVALID)
        )

        cases.forEach { case ->
            val actual = diagnoseCaptureGeometry(
                frameWidth = case.frameWidth,
                frameHeight = case.frameHeight,
                overlayWidth = case.overlayWidth,
                overlayHeight = case.overlayHeight
            )

            assertEquals(case.name, case.expected, actual.relation)
            assertEquals(case.name, case.frameWidth, actual.frameWidth)
            assertEquals(case.name, case.frameHeight, actual.frameHeight)
            assertEquals(case.name, case.overlayWidth, actual.overlayWidth)
            assertEquals(case.name, case.overlayHeight, actual.overlayHeight)
        }
    }

    @Test
    fun shouldResizeProjection_tableDriven_handlesRotationAndSizeChanges() {
        data class Case(
            val name: String,
            val currentWidth: Int,
            val currentHeight: Int,
            val targetWidth: Int,
            val targetHeight: Int,
            val expected: Boolean
        )

        val cases = listOf(
            Case("same portrait size", 1440, 3200, 1440, 3200, false),
            Case("same landscape size", 3200, 1440, 3200, 1440, false),
            Case("portrait to landscape", 1440, 3200, 3200, 1440, true),
            Case("landscape to portrait", 3200, 1440, 1440, 3200, true),
            Case("same orientation resolution change", 3200, 1440, 2560, 1080, true),
            Case("invalid zero width target", 1440, 3200, 0, 3200, false),
            Case("invalid zero height target", 1440, 3200, 1440, 0, false),
            Case("invalid negative target", 1440, 3200, -1, -1, false)
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldResizeProjection(
                    currentWidth = case.currentWidth,
                    currentHeight = case.currentHeight,
                    targetWidth = case.targetWidth,
                    targetHeight = case.targetHeight
                )
            )
        }
    }
}
