package com.gameocr.app.capture

import org.junit.Assert.*
import org.junit.Test

class SettledPageVisualPolicyTest {
    private fun frame(
        source: Int = 40,
        overlay: IntRange = IntRange.EMPTY,
        overlayColor: Int = 255,
    ) = SettledPageFrame(
        10, 10, 1,
        ByteArray(100) { if (it in overlay) overlayColor.toByte() else source.toByte() },
        BooleanArray(100) { it !in overlay },
    )

    @Test fun translationAppearanceStreamingMovementAndRemovalDoNotChangeSource() {
        data class Case(val name: String, val before: SettledPageFrame, val after: SettledPageFrame)
        listOf(
            Case("blocks appear", frame(), frame(overlay = 0..39)),
            Case("streaming changes text", frame(overlay = 0..39), frame(overlay = 0..39, overlayColor = 0)),
            Case("translated paragraph wraps", frame(overlay = 0..19), frame(overlay = 0..49)),
            Case("floating window moves", frame(overlay = 0..39), frame(overlay = 40..79)),
            Case("translation dismissed", frame(overlay = 0..39), frame()),
            Case("repair patch replaced", frame(overlay = 20..69), frame(overlay = 10..79, overlayColor = 220)),
        ).forEach { case ->
            assertEquals(case.name, 1f, SettledPageVisualPolicy.similarity(case.before, case.after)!!, 0f)
        }
    }

    @Test fun pageTurnOutsideOverlayStillInvalidatesAndOnlyLatestPageIsSubmitted() {
        val schedule = SettledPagePolicy()
        var anchor: SettledPageFrame? = null
        val submitted = mutableListOf<Long>()
        val invalidated = mutableListOf<Long>()
        data class Step(val time: Long, val frame: SettledPageFrame)
        listOf(
            Step(0, frame()), Step(500, frame()),
            Step(1000, frame(overlay = 0..39)),
            Step(1500, frame(overlay = 0..49, overlayColor = 0)),
            Step(2000, frame(source = 200, overlay = 0..49)), // turn while old result visible
            Step(2100, frame(source = 120)), // next page before intermediate page settles
            Step(2600, frame(source = 120)),
            Step(3100, frame(source = 120, overlay = 30..79)),
        ).forEach { step ->
            val same = anchor?.let { SettledPageVisualPolicy.similarity(it, step.frame)!! >= .95f } ?: false
            val decision = schedule.observe(same, true, step.time, 500)
            if (decision.replaceAnchor) anchor = step.frame
            if (decision.invalidatePrevious) invalidated += decision.revision
            if (decision.ready && schedule.claim(decision.revision)) submitted += decision.revision
        }
        assertEquals(listOf(1L, 3L), submitted)
        assertEquals(listOf(2L, 3L), invalidated)
        assertFalse("late first-page request must be rejected", schedule.accepts(submitted.first()))
        assertTrue(schedule.accepts(submitted.last()))
    }

    @Test fun commonVisibilityNotIndividualVisibilityDeterminesWhetherFrameCanBeCompared() {
        data class Case(val name: String, val before: SettledPageFrame, val after: SettledPageFrame, val enough: Boolean)
        listOf(
            Case("all obscured", frame(), frame(overlay = 0..99), false),
            Case("only nine percent source", frame(), frame(overlay = 0..90), false),
            Case("ten percent source", frame(), frame(overlay = 0..89), true),
            Case("each half visible but no common source", frame(overlay = 0..49), frame(overlay = 50..99), false),
            Case("empty sample", frame().copy(luminance = byteArrayOf(), visible = booleanArrayOf()), frame(), false),
            Case("invalid mask", frame().copy(visible = booleanArrayOf()), frame(), false),
        ).forEach { case ->
            assertEquals(case.name, case.enough, SettledPageVisualPolicy.similarity(case.before, case.after) != null)
        }
    }

    @Test fun gradualChangeIsMeasuredAgainstAnchorNotOnlyPreviousSample() {
        val anchor = frame()
        val near = frame(source = 48)
        val far = frame(source = 56)
        assertTrue(SettledPageVisualPolicy.similarity(anchor, near)!! >= .95f)
        assertTrue(SettledPageVisualPolicy.similarity(near, far)!! >= .95f)
        assertTrue(SettledPageVisualPolicy.similarity(anchor, far)!! < .95f)
    }

    @Test fun contextChangesCannotReuseOldSamples() {
        val original = frame()
        listOf(
            original.copy(width = 20), original.copy(height = 20), original.copy(contextId = 2),
            original.copy(luminance = ByteArray(99)),
        ).forEach { changed ->
            assertFalse(SettledPageVisualPolicy.comparable(original, changed))
            assertNull(SettledPageVisualPolicy.similarity(original, changed))
        }
    }

    @Test fun sampleMasksClipAndScaleWithoutTurningTransparentHostIntoFullScreenMask() {
        data class Case(val name: String, val width: Int, val height: Int, val rect: OverlayCaptureRect, val visible: Int)
        listOf(
            Case("single child", 100, 100, OverlayCaptureRect(40, 40, 60, 60), 84),
            Case("landscape capture", 200, 100, OverlayCaptureRect(80, 40, 120, 60), 84),
            Case("portrait capture", 100, 200, OverlayCaptureRect(40, 80, 60, 120), 84),
            Case("partially offscreen", 100, 100, OverlayCaptureRect(-20, -20, 10, 10), 96),
            Case("fully outside", 100, 100, OverlayCaptureRect(-20, -20, -10, -10), 100),
            Case("empty", 100, 100, OverlayCaptureRect(10, 10, 10, 20), 100),
            Case("fullscreen menu", 100, 100, OverlayCaptureRect(0, 0, 100, 100), 0),
        ).forEach { case ->
            val mask = SettledPageVisualPolicy.visibility(case.width, case.height, 10, listOf(case.rect))
            assertEquals(case.name, case.visible, mask.count { it })
        }
    }
}
