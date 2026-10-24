package com.gameocr.app.overlay

import com.gameocr.app.capture.LoopIndicatorMode
import com.gameocr.app.capture.LoopIndicatorSpec
import org.junit.Assert.*
import org.junit.Test

class FloatingLoopProgressStateTest {
    private val countdown = LoopIndicatorSpec(LoopIndicatorMode.COUNTDOWN, 3000L)
    private val rotating = LoopIndicatorSpec(LoopIndicatorMode.INDETERMINATE, 1600L)

    @Test fun recreatingTheBallRestoresTheSameModeAndPeriod_tableDriven() {
        for (spec in listOf(countdown, rotating)) {
            val state = FloatingLoopProgressState()
            val oldWindow = mutableListOf<LoopIndicatorSpec?>()
            state.attach { oldWindow += it }
            state.update(spec)
            state.detach()
            assertTrue("the loop keeps running while the ball is absent", state.isActive)
            val newWindow = mutableListOf<LoopIndicatorSpec?>()
            state.attach { newWindow += it }
            assertEquals(listOf(null, spec), oldWindow)
            assertEquals(listOf(spec), newWindow)
        }
    }

    @Test fun stoppingWhileHiddenDoesNotResurrectTheIndicator_tableDriven() {
        for (spec in listOf(countdown, rotating)) {
            val state = FloatingLoopProgressState()
            val oldWindow = mutableListOf<LoopIndicatorSpec?>()
            state.update(spec)
            state.attach { oldWindow += it }
            state.detach()
            state.update(null)
            assertFalse(state.isActive)
            val newWindow = mutableListOf<LoopIndicatorSpec?>()
            state.attach { newWindow += it }
            assertEquals("a removed window must not receive updates", listOf(spec), oldWindow)
            assertEquals(listOf(null), newWindow)
        }
    }

    @Test fun updatesWhileHiddenOnlyRenderTheLatestStateWhenTheBallReturns_tableDriven() {
        listOf(
            listOf(countdown, rotating),
            listOf(rotating, null, countdown.copy(periodMs = 5000L)),
            listOf(countdown, null, rotating, null),
        ).forEach { updates ->
            val state = FloatingLoopProgressState()
            updates.forEach(state::update)
            val window = mutableListOf<LoopIndicatorSpec?>()
            state.attach { window += it }
            assertEquals(listOf(updates.last()), window)
            assertEquals(updates.last() != null, state.isActive)
        }
    }

    @Test fun repeatedHideAndShowDoesNotKeepCallbacksToOldWindows() {
        val state = FloatingLoopProgressState()
        state.update(rotating)
        val windows = List(4) { mutableListOf<LoopIndicatorSpec?>() }
        windows.forEach { window ->
            state.attach { window += it }
            state.detach()
            state.detach()
        }
        state.update(null)
        windows.forEach { assertEquals(listOf(rotating), it) }
        assertFalse(state.isActive)
    }

    @Test fun anIdleLoopStaysIdleAndAnAttachedWindowReceivesTheStop() {
        val state = FloatingLoopProgressState()
        val window = mutableListOf<LoopIndicatorSpec?>()
        state.detach()
        state.attach { window += it }
        assertFalse(state.isActive)
        state.update(countdown)
        state.update(null)
        assertEquals(listOf(null, countdown, null), window)
        assertFalse(state.isActive)
    }
}
