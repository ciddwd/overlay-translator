package com.gameocr.app.service

import com.gameocr.app.R
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.InputTranslationDoubleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FloatingSkillSwitchHintTest {
    @Test fun everyModeUsesItsOwnHintRegardlessOfAnUnrelatedDoubleTapSetting_tableDriven() {
        val nonInputModes = listOf(
            FloatingSkill.FULL_SCREEN to R.string.toast_skill_switched_full_screen,
            FloatingSkill.WORD_SELECT to R.string.toast_skill_switched_word_select,
            FloatingSkill.LOOP to R.string.toast_skill_switched_loop,
            FloatingSkill.GESTURE to R.string.toast_skill_switched_gesture,
        )
        for ((mode, expected) in nonInputModes) {
            for (doubleAction in listOf(null) + InputTranslationDoubleAction.entries) {
                assertEquals("$mode / $doubleAction", expected, floatingSkillSwitchHintRes(mode, doubleAction))
            }
        }
    }

    @Test fun inputHintFollowsBothDoubleTapChoicesAndFallsBackToTheActualDefault_tableDriven() {
        listOf(
            null to R.string.toast_skill_switched_input_translate,
            InputTranslationDoubleAction.FULL_SCREEN to R.string.toast_skill_switched_input_translate,
            InputTranslationDoubleAction.WORD_SELECT to R.string.toast_skill_switched_input_translate_word_select,
        ).forEach { (doubleAction, expected) ->
            assertEquals("$doubleAction", expected, floatingSkillSwitchHintRes(FloatingSkill.INPUT_TRANSLATE, doubleAction))
        }
        assertEquals(R.string.toast_skill_switched_input_translate, floatingSkillSwitchHintRes(FloatingSkill.INPUT_TRANSLATE))
    }

    @Test fun gestureSwitchDoesNotReuseTheShortModeName() {
        assertNotEquals(R.string.gesture_mode, floatingSkillSwitchHintRes(FloatingSkill.GESTURE))
    }
}
