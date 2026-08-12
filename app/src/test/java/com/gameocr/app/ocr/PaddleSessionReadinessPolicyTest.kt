package com.gameocr.app.ocr

import com.gameocr.app.data.PaddleModelVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleSessionReadinessPolicyTest {

    @Test
    fun decision_tableDriven_preservesVersionSwitchAndReuseSemantics() {
        data class Case(
            val name: String,
            val requested: PaddleModelVersion,
            val loaded: PaddleModelVersion?,
            val detReady: Boolean,
            val recReady: Boolean,
            val expectedInvalidate: Boolean,
            val expectedReuse: Boolean,
        )

        listOf(
            Case(
                name = "cold start",
                requested = PaddleModelVersion.V6_SMALL,
                loaded = null,
                detReady = false,
                recReady = false,
                expectedInvalidate = true,
                expectedReuse = false,
            ),
            Case(
                name = "same version with both sessions",
                requested = PaddleModelVersion.V6_SMALL,
                loaded = PaddleModelVersion.V6_SMALL,
                detReady = true,
                recReady = true,
                expectedInvalidate = false,
                expectedReuse = true,
            ),
            Case(
                name = "same version missing detector",
                requested = PaddleModelVersion.V6_SMALL,
                loaded = PaddleModelVersion.V6_SMALL,
                detReady = false,
                recReady = true,
                expectedInvalidate = false,
                expectedReuse = false,
            ),
            Case(
                name = "same version missing recognizer",
                requested = PaddleModelVersion.V6_SMALL,
                loaded = PaddleModelVersion.V6_SMALL,
                detReady = true,
                recReady = false,
                expectedInvalidate = false,
                expectedReuse = false,
            ),
            Case(
                name = "switch from v5 to v6 small",
                requested = PaddleModelVersion.V6_SMALL,
                loaded = PaddleModelVersion.V5_MOBILE,
                detReady = true,
                recReady = true,
                expectedInvalidate = true,
                expectedReuse = false,
            ),
            Case(
                name = "switch between v6 sizes",
                requested = PaddleModelVersion.V6_MEDIUM,
                loaded = PaddleModelVersion.V6_TINY,
                detReady = true,
                recReady = true,
                expectedInvalidate = true,
                expectedReuse = false,
            ),
        ).forEach { case ->
            val actual = paddleSessionReadinessDecision(
                requestedVersion = case.requested,
                loadedVersion = case.loaded,
                detSessionReady = case.detReady,
                recSessionReady = case.recReady,
            )

            assertEquals(case.name, case.expectedInvalidate, actual.invalidateLoadedSessions)
            assertEquals(case.name, case.expectedReuse, actual.reuseLoadedSessions)
        }
    }
}
