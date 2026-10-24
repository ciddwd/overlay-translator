package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.MenuItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpeechMenuSlotTest {
    @Test fun speechSlotAlwaysFollowsLastModeSlot_tableDriven() {
        data class Case(val name: String, val order: List<MenuItemId>, val expected: List<MenuItemId>)
        val noSkills = listOf(MenuItemId.REGION, MenuItemId.HOME)
        listOf(
            Case(
                "after gesture slot", FloatingMenu.DEFAULT_ORDER,
                FloatingMenu.DEFAULT_ORDER.toMutableList().apply {
                    add(indexOf(MenuItemId.GESTURE_SKILL) + 1, MenuItemId.SPEECH_SKILL)
                },
            ),
            Case("no mode slots appends", noSkills, noSkills + MenuItemId.SPEECH_SKILL),
            Case("never duplicated", noSkills + MenuItemId.SPEECH_SKILL, noSkills + MenuItemId.SPEECH_SKILL),
            Case("stale position corrected", listOf(MenuItemId.SPEECH_SKILL) + noSkills, noSkills + MenuItemId.SPEECH_SKILL),
            Case("empty", emptyList(), listOf(MenuItemId.SPEECH_SKILL)),
        ).forEach { case ->
            assertEquals(case.name, case.expected, MenuItemRegistry.withSpeechSlot(case.order))
        }
    }

    @Test fun speechSlotIsNeverPersistedInSavedOrders() {
        assertFalse(MenuItemId.SPEECH_SKILL in FloatingMenu.ALL_ORDER)
        assertEquals(FloatingMenu.DEFAULT_ORDER, FloatingMenu.normalizeOrder(FloatingMenu.DEFAULT_ORDER + MenuItemId.SPEECH_SKILL))
    }

    @Test fun modeSlotsTargetSpeechAndLeaveIt_tableDriven() {
        data class Case(val slot: MenuItemId, val current: FloatingSkill, val expected: FloatingSkill)
        listOf(
            Case(MenuItemId.SPEECH_SKILL, FloatingSkill.FULL_SCREEN, FloatingSkill.SPEECH),
            Case(MenuItemId.SPEECH_SKILL, FloatingSkill.GESTURE, FloatingSkill.SPEECH),
            Case(MenuItemId.SPEECH_SKILL, FloatingSkill.SPEECH, FloatingSkill.WORD_SELECT),
            Case(MenuItemId.FULL_SCREEN_SKILL, FloatingSkill.SPEECH, FloatingSkill.FULL_SCREEN),
            Case(MenuItemId.INPUT_TRANSLATE_SKILL, FloatingSkill.SPEECH, FloatingSkill.INPUT_TRANSLATE),
            Case(MenuItemId.GESTURE_SKILL, FloatingSkill.SPEECH, FloatingSkill.GESTURE),
            Case(MenuItemId.LOOP, FloatingSkill.SPEECH, FloatingSkill.LOOP),
        ).forEach { case ->
            assertEquals("${case.slot} from ${case.current}", case.expected, MenuItemRegistry.targetSkill(case.slot, case.current))
        }
    }

    @Test fun speechMenuItemDispatchesSpeechCallback() {
        var tapped = false
        val callbacks = MenuItemRegistry.Callbacks(
            onSwitchToLoop = {}, onRegion = {}, onLanguagePair = {}, onOpenMain = {}, onOpenSettings = {},
            onPresetSwitch = {}, onSwitchToFullScreen = {}, onSwitchToWordSelect = {},
            onSwitchToInputTranslate = {}, onSwitchToGesture = {}, onSwitchToSpeech = { tapped = true },
        )
        MenuItemRegistry.build(listOf(MenuItemId.SPEECH_SKILL), FloatingSkill.FULL_SCREEN, callbacks).single().onTap()
        assertEquals(true, tapped)
    }
}
