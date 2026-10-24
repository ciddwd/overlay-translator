package com.gameocr.app.ui

import com.gameocr.app.R
import org.junit.Assert.*
import org.junit.Test

class FloatingSettingsPagesTest {
    @Test fun quickPanelDisablesOnlyTheArcMenuPage_tableDriven() {
        for (quickPanel in listOf(false, true)) {
            for (page in FloatingSettingsPage.entries) {
                assertEquals("$quickPanel / $page", !(quickPanel && page == FloatingSettingsPage.MENU),
                    canOpenFloatingSettingsPage(page, quickPanel))
            }
        }
    }

    @Test fun searchTargetsReachTheirActualDetailPages_tableDriven() {
        val targets = mapOf(
            R.string.settings_search_item_floating_snap to FloatingSettingsPage.DOCKING,
            R.string.settings_search_item_floating_auto_dock to FloatingSettingsPage.DOCKING,
            R.string.settings_search_item_floating_dock_inset to FloatingSettingsPage.DOCKING,
            R.string.settings_search_item_arc_menu_order to FloatingSettingsPage.MENU,
            R.string.gesture_mode to FloatingSettingsPage.WAKE,
            R.string.gesture_wake_title to FloatingSettingsPage.WAKE,
            R.string.gesture_binding_title to FloatingSettingsPage.BINDINGS,
        )
        targets.forEach { (target, page) -> assertEquals(page, floatingSettingsPageForTarget(target)) }
        listOf(-1, 0, Int.MAX_VALUE, R.string.settings_search_item_floating_size,
            R.string.settings_search_item_floating_alpha, R.string.settings_quick_panel_enabled).forEach {
            assertNull("Unrelated/root controls must stay on the main settings page", floatingSettingsPageForTarget(it))
        }
    }

    @Test fun searchCannotBypassTheDisabledArcMenuEntry_tableDriven() {
        for (enabled in listOf(false, true)) {
            assertEquals(if (enabled) null else FloatingSettingsPage.MENU,
                floatingSettingsPageForTarget(R.string.settings_search_item_arc_menu_order, enabled))
            assertEquals(FloatingSettingsPage.WAKE, floatingSettingsPageForTarget(R.string.gesture_mode, enabled))
            assertEquals(FloatingSettingsPage.BINDINGS,
                floatingSettingsPageForTarget(R.string.gesture_binding_title, enabled))
        }
    }
}
