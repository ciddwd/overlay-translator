package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.MenuItemId
import org.junit.Assert.*
import org.junit.Test

class FloatingQuickPanelPolicyTest {
    @Test fun layoutAdaptsToCurrentViewportAndNarrowSplitWindows_tableDriven() {
        data class Case(val width: Int, val height: Int, val expectedWidth: Int, val sideBySide: Boolean)
        listOf(
            Case(400, 800, 320, false),
            Case(900, 380, 560, true),
            Case(500, 300, 500, true),
            Case(479, 300, 320, false),
            Case(480, 300, 480, true),
            Case(600, 600, 320, false),
            Case(280, 800, 280, false),
            Case(0, 0, 1, false),
            Case(-10, -30, 1, false),
        ).forEach { case ->
            assertEquals(case.toString(), QuickPanelLayout(case.expectedWidth, case.sideBySide),
                FloatingQuickPanelPolicy.layout(case.width, case.height, 320, 560, 480))
        }
        // Pixel density scales all dimensions together; it must not change the selected layout.
        for (scale in listOf(1, 2, 4)) {
            assertEquals(QuickPanelLayout(560 * scale, true),
                FloatingQuickPanelPolicy.layout(900 * scale, 380 * scale, 320 * scale, 560 * scale, 480 * scale))
        }
        assertEquals(QuickPanelLayout(1, false), FloatingQuickPanelPolicy.layout(300, 600, 0, -1, 480))
    }

    @Test fun panelSurfacesAreFullyOpaque() {
        listOf(FloatingQuickPanelView.panelColor, FloatingQuickPanelView.actionColor,
            FloatingQuickPanelView.selectedColor).forEach { assertEquals(255, it ushr 24) }
    }

    @Test fun sixModesAndFourActionsStayStableForEveryCurrentMode() {
        // Speech mode is always available; every mode is listed once, speech last.
        assertEquals(FloatingSkill.entries.toSet(), FloatingQuickPanelPolicy.modes.toSet())
        assertEquals(6, FloatingQuickPanelPolicy.modes.size)
        assertEquals(FloatingSkill.SPEECH, FloatingQuickPanelPolicy.modes.last())
        assertEquals(listOf(MenuItemId.REGION, MenuItemId.LANGUAGE_PAIR, MenuItemId.PRESET_SWITCH,
            MenuItemId.SETTINGS), FloatingQuickPanelPolicy.actions)
        for (current in FloatingSkill.entries) for (target in FloatingQuickPanelPolicy.modes) {
            assertEquals("$current -> $target", target.takeIf { it != current },
                FloatingQuickPanelPolicy.modeChange(current, target))
        }
    }

    @Test fun preferenceKeepsLegacyMenuAndGuidedTourAvailable_tableDriven() {
        for (enabled in listOf(false, true)) for (tour in listOf(false, true)) {
            assertEquals(enabled && !tour, FloatingQuickPanelPolicy.usePanel(enabled, tour))
        }
    }

    @Test fun realMenuItemsDispatchToModeSelectionAndFourExistingShortcuts() {
        val events = mutableListOf<String>()
        val callbacks = MenuItemRegistry.Callbacks(
            onSwitchToLoop = { events += "LOOP" }, onSwitchToFullScreen = { events += "FULL_SCREEN" },
            onSwitchToWordSelect = { events += "WORD_SELECT" }, onSwitchToInputTranslate = { events += "INPUT_TRANSLATE" },
            onSwitchToGesture = { events += "GESTURE" }, onSwitchToSpeech = { events += "SPEECH" },
            onRegion = { events += "REGION" },
            onLanguagePair = { events += "LANGUAGE_PAIR" }, onPresetSwitch = { events += "PRESET_SWITCH" },
            onOpenSettings = { events += "SETTINGS" }, onOpenMain = { events += "HOME" },
        )
        FloatingQuickPanelPolicy.modes.map { MenuItemRegistry.buildSkillItem(it, callbacks) }.forEach { it.onTap() }
        assertEquals(FloatingQuickPanelPolicy.modes.map { it.name }, events)
        events.clear()
        MenuItemRegistry.build(FloatingQuickPanelPolicy.actions, FloatingSkill.GESTURE, callbacks).forEach { it.onTap() }
        assertEquals(FloatingQuickPanelPolicy.actions.map { it.name }, events)
        events.clear()
        MenuItemRegistry.build(listOf(MenuItemId.HOME), FloatingSkill.GESTURE, callbacks).single().onTap()
        assertEquals(listOf("HOME"), events)
    }

    @Test fun panelFitsCornersInsetsLandscapeAndOversizedContent_tableDriven() {
        data class Case(val right: Int, val bottom: Int, val anchor: List<Int>, val width: Int, val height: Int)
        listOf(
            Case(400, 800, listOf(345, 100, 395, 156), 320, 360),
            Case(400, 800, listOf(8, 700, 64, 756), 320, 360),
            Case(400, 800, listOf(150, 300, 206, 356), 320, 360),
            Case(780, 340, listOf(710, 30, 766, 86), 320, 900),
            Case(280, 800, listOf(220, 700, 276, 756), 320, 1100),
            Case(9, 25, listOf(0, 0, 1, 1), 0, 0),
        ).forEach { case ->
            val a = case.anchor
            val placed = FloatingQuickPanelPolicy.place(8, 24, case.right, case.bottom,
                a[0], a[1], a[2], a[3], case.width, case.height, 8)
            assertTrue(case.toString(), placed.left >= 8 && placed.top >= 24)
            assertTrue(case.toString(), placed.left + placed.width <= case.right)
            assertTrue(case.toString(), placed.top + placed.height <= case.bottom)
            assertTrue(placed.width > 0 && placed.height > 0)
        }
        val left = FloatingQuickPanelPolicy.place(8, 24, 400, 800, 350, 400, 390, 456, 320, 360, 8)
        assertEquals(22, left.left)
        assertEquals(248, left.top)
    }
}
