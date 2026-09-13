package com.gameocr.app.capture

import com.gameocr.app.data.LoopTriggerMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopRuntimePolicyTest {

    @Test
    fun activeResultDecision_coversLoopModesTranslationAndTimingBoundaries() {
        data class Case(
            val name: String,
            val hasBlockingResult: Boolean,
            val translationInFlight: Boolean,
            val expected: LoopActiveResultDecision,
        )

        listOf(
            Case("no result captures", false, false,
                LoopActiveResultDecision.CAPTURE),
            Case("persistent floating result does not block capture", false, false,
                LoopActiveResultDecision.CAPTURE),
            Case("translation blocks capture even after manual dismiss", false, true,
                LoopActiveResultDecision.KEEP_TRANSLATING),
            Case("active translating result stays visible", true, true,
                LoopActiveResultDecision.KEEP_TRANSLATING),
            Case("blocking overlay result requires manual dismiss", true, false,
                LoopActiveResultDecision.KEEP_VISIBLE),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopRuntimePolicy.activeResultDecision(
                    hasBlockingResult = case.hasBlockingResult,
                    translationInFlight = case.translationInFlight,
                ),
            )
        }
    }

    @Test
    fun indicatorSpec_coversInvalidValuesAndBothModes() {
        data class IndicatorCase(
            val name: String,
            val intervalMs: Long,
            val smartMode: Boolean,
            val expected: LoopIndicatorSpec,
        )
        listOf(
            IndicatorCase(
                "fixed uses configured countdown",
                3000L,
                false,
                LoopIndicatorSpec(LoopIndicatorMode.COUNTDOWN, 3000L),
            ),
            IndicatorCase(
                "invalid fixed interval uses default",
                0L,
                false,
                LoopIndicatorSpec(LoopIndicatorMode.COUNTDOWN, 2000L),
            ),
            IndicatorCase(
                "smart ignores polling interval and rotates smoothly",
                200L,
                true,
                LoopIndicatorSpec(LoopIndicatorMode.INDETERMINATE, 1600L),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopRuntimePolicy.indicatorSpec(case.intervalMs, case.smartMode),
            )
        }
    }

    @Test
    fun pollingInterval_coversEveryTriggerAndBackendFloor() {
        data class Case(
            val name: String,
            val configuredMs: Long,
            val mode: LoopTriggerMode,
            val backendMinimumMs: Long,
            val expectedMs: Long,
        )

        listOf(
            Case("fixed keeps user interval", 1500L, LoopTriggerMode.FIXED_INTERVAL, 0L, 1500L),
            Case("text completion observes frequently", 1500L, LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE, 0L, 200L),
            Case("settled page observes frequently", 1500L, LoopTriggerMode.SETTLED_PAGE, 0L, 200L),
            Case("Shizuku floor avoids process churn", 1500L, LoopTriggerMode.SETTLED_PAGE, 350L, 350L),
            Case("backend floor never slows a longer fixed interval", 2000L, LoopTriggerMode.FIXED_INTERVAL, 350L, 2000L),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedMs,
                LoopRuntimePolicy.pollingIntervalMs(
                    configuredLoopIntervalMs = case.configuredMs,
                    mode = case.mode,
                    backendMinimumMs = case.backendMinimumMs,
                ),
            )
        }
    }
}
