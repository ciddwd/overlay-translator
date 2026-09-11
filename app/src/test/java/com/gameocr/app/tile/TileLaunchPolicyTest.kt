package com.gameocr.app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLaunchPolicyTest {

    @Test
    fun launchApi_boundaryAt34() {
        val cases = listOf(
            24 to TileLaunchApi.LEGACY_INTENT,
            26 to TileLaunchApi.LEGACY_INTENT,
            29 to TileLaunchApi.LEGACY_INTENT,
            30 to TileLaunchApi.LEGACY_INTENT,
            31 to TileLaunchApi.LEGACY_INTENT,
            33 to TileLaunchApi.LEGACY_INTENT,
            34 to TileLaunchApi.PENDING_INTENT,
            35 to TileLaunchApi.PENDING_INTENT,
            36 to TileLaunchApi.PENDING_INTENT,
        )
        cases.forEach { (sdkInt, expected) ->
            assertEquals("sdkInt=$sdkInt", expected, resolveTileLaunchApi(sdkInt))
        }
    }

    @Test
    fun legacyBranchIsExactlyBelow34() {
        (0..33).forEach { sdkInt ->
            assertEquals(
                "sdkInt=$sdkInt must use the legacy overload",
                TileLaunchApi.LEGACY_INTENT,
                resolveTileLaunchApi(sdkInt),
            )
        }
        (34..40).forEach { sdkInt ->
            assertEquals(
                "sdkInt=$sdkInt must use the PendingIntent overload",
                TileLaunchApi.PENDING_INTENT,
                resolveTileLaunchApi(sdkInt),
            )
        }
    }

    @Test
    fun creatorOptIn_onlyFrom34() {
        assertFalse(needsPendingIntentCreatorOptIn(33))
        assertTrue(needsPendingIntentCreatorOptIn(34))
        assertTrue(needsPendingIntentCreatorOptIn(35))
        assertTrue(needsPendingIntentCreatorOptIn(36))
    }
}
