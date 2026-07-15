package com.gameocr.app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopRoiFallbackPolicyTest {
    @Test
    fun transition_keepsFallbackUntilTextPipelineFinishes() {
        data class Case(
            val name: String,
            val active: Boolean,
            val event: LoopRoiFallbackEvent,
            val expected: Boolean,
        )
        listOf(
            Case("ROI timeout enters fallback", false, LoopRoiFallbackEvent.ENTER, true),
            Case("re-enter stays active", true, LoopRoiFallbackEvent.ENTER, true),
            Case("first OCR text observation keeps waiting", true, LoopRoiFallbackEvent.TEXT_STILL_WAITING, true),
            Case("waiting cannot activate fallback by itself", false, LoopRoiFallbackEvent.TEXT_STILL_WAITING, false),
            Case("translation clears fallback", true, LoopRoiFallbackEvent.TEXT_FINISHED, false),
            Case("duplicate skip clears fallback", true, LoopRoiFallbackEvent.TEXT_FINISHED, false),
            Case("loop stop resets fallback", true, LoopRoiFallbackEvent.RESET, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                LoopRoiFallbackPolicy.transition(case.active, case.event),
            )
        }
    }
}
