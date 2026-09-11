package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStartRecoveryPolicyTest {

    @Test
    fun recovery_tableDriven_handsOffOnlyTheTokenlessShizukuRequest() {
        data class Case(
            val name: String,
            val tokenPresent: Boolean,
            val shizukuRequested: Boolean,
            val expected: CaptureStartRecovery,
        )

        listOf(
            Case(
                "direct start lost the Shizuku race",
                tokenPresent = false,
                shizukuRequested = true,
                expected = CaptureStartRecovery.OPEN_PROJECTION_GATEWAY,
            ),
            Case(
                "gateway start failed again",
                tokenPresent = true,
                shizukuRequested = false,
                expected = CaptureStartRecovery.NONE,
            ),
            Case(
                "no token and no Shizuku request",
                tokenPresent = false,
                shizukuRequested = false,
                expected = CaptureStartRecovery.NONE,
            ),
            Case(
                "token present despite a Shizuku request",
                tokenPresent = true,
                shizukuRequested = true,
                expected = CaptureStartRecovery.NONE,
            ),
        ).forEach { case ->
            assertEquals(
                "${case.name}: tokenPresent=${case.tokenPresent} shizukuRequested=${case.shizukuRequested}",
                case.expected,
                decideCaptureStartRecovery(
                    CaptureStartRecoveryInput(case.tokenPresent, case.shizukuRequested)
                ),
            )
        }
    }

    @Test
    fun aStartThatAlreadyCameFromTheGatewayNeverReopensIt() {
        val reopened = listOf(false, true).filter { shizukuRequested ->
            decideCaptureStartRecovery(
                CaptureStartRecoveryInput(tokenPresent = true, shizukuRequested = shizukuRequested)
            ) == CaptureStartRecovery.OPEN_PROJECTION_GATEWAY
        }
        assertEquals(
            "a token-bearing intent was started by the gateway itself: reopening it would bounce the " +
                "user between the system dialog and a failing service forever",
            emptyList<Boolean>(),
            reopened,
        )
    }

    @Test
    fun onlyTheDirectStartEverNeedsAnIntervention() {
        val intervenes = listOf(false, true).filter { tokenPresent ->
            decideCaptureStartRecovery(
                CaptureStartRecoveryInput(tokenPresent = tokenPresent, shizukuRequested = true)
            ) == CaptureStartRecovery.OPEN_PROJECTION_GATEWAY
        }
        assertEquals(
            "the tile is the only caller that requests Shizuku without a token, so it must be the only " +
                "one the service intervenes for",
            listOf(false),
            intervenes,
        )
    }
}
