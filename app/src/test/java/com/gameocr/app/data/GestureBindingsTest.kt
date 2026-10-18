package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureBindingsTest {
    @Test fun defaultsCoverExactlyEightGestures() {
        assertEquals(8, PresetGesture.entries.size)
        assertEquals(PresetGesture.entries.toSet(), GestureBindings.defaults.keys)
    }

    @Test fun partialAndFutureSettingsKeepValidBindingsAndFillMissingOnes() {
        val restored = GestureBindings.fromNames(mapOf(
            "UP" to "SWITCH_WORD_SELECT", "S" to "NONE", "O" to "FUTURE_ACTION", "FUTURE_GESTURE" to "HOME",
        ))
        assertEquals(GestureAction.NONE, restored[PresetGesture.UP])
        assertEquals(GestureAction.NONE, restored[PresetGesture.S])
        assertEquals(GestureBindings.defaults[PresetGesture.O], restored[PresetGesture.O])
        assertEquals(8, restored.size)
    }

    @Test fun modeBindingsAreNeitherOfferedNorPreserved() {
        assertEquals(10, GestureBindings.actions.size)
        GestureBindings.actions.forEach { assertNull(it.targetSkill) }
        val legacy = PresetGesture.entries.associateWith { GestureAction.SWITCH_GESTURE }
        assertEquals(setOf(GestureAction.NONE), GestureBindings.normalize(legacy).values.toSet())
    }
}
