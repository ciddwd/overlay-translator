package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureEdgeSessionTest {
    @Test fun entryMotionCannotStartDrawing() {
        val session = GestureEdgeSession(GesturePoint(80f, 100f), 0, 1f)
        session.move(GesturePoint(180f, 100f), 100)
        session.move(GesturePoint(240f, 100f), 200)
        session.pause(439)
        assertEquals(GestureEdgeSession.Phase.WAITING, session.phase)
        session.pause(440)
        assertEquals(GestureEdgeSession.Phase.READY, session.phase)
        // Lifting at WAITING or READY must leave the layer open, without executing.
        assertEquals(GesturePoint(240f, 100f), session.anchor)
    }

    @Test fun drawingStartsAtPausePointAndExcludesEntryPrefix() {
        val session = GestureEdgeSession(GesturePoint(80f, 100f), 0, 1f)
        session.move(GesturePoint(200f, 100f), 100)
        session.pause(340)
        session.move(GesturePoint(203f, 101f), 350)
        assertEquals(GestureEdgeSession.Phase.READY, session.phase)
        session.move(GesturePoint(200f, 110f), 360)
        assertEquals(GestureEdgeSession.Phase.DRAWING, session.phase)
        val points = listOf(session.anchor, GesturePoint(200f, 160f), GesturePoint(200f, 220f))
        assertEquals(com.gameocr.app.data.PresetGesture.DOWN, PresetGestureRecognizer.recognize(points, 40f))
    }

    @Test fun pauseToleratesJitterAndScalesWithDensity() {
        val session = GestureEdgeSession(GesturePoint(200f, 100f), 10, 2f)
        session.move(GesturePoint(208f, 100f), 150)
        session.pause(250)
        assertEquals(GestureEdgeSession.Phase.READY, session.phase)
        assertEquals(GesturePoint(208f, 100f), session.anchor)
        session.move(GesturePoint(220f, 100f), 270)
        assertEquals(GestureEdgeSession.Phase.READY, session.phase)
        session.move(GesturePoint(225f, 100f), 280)
        assertEquals(GestureEdgeSession.Phase.DRAWING, session.phase)
    }
}
