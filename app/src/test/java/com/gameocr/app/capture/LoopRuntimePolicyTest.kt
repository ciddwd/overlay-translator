package com.gameocr.app.capture

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
}
