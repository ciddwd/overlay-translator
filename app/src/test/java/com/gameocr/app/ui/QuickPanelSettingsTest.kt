package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsFieldPolicy
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class QuickPanelSettingsTest {
    @Test fun preferenceDefaultsOffAndRoundTripsWithoutChangingOtherMenuSettings_tableDriven() {
        assertFalse(Settings().quickPanelEnabled)
        assertFalse(SettingsFieldPolicy.decodePortable(JsonObject(emptyMap())).settings.quickPanelEnabled)
        for (enabled in listOf(false, true)) {
            val original = Settings(quickPanelEnabled = enabled, arcMenuPageSize = 3)
            val decoded = SettingsFieldPolicy.decodePortable(SettingsFieldPolicy.encodePortable(original)).settings
            assertEquals(enabled, decoded.quickPanelEnabled)
            assertEquals(original.floatingMenuItemOrder, decoded.floatingMenuItemOrder)
            assertEquals(3, decoded.arcMenuPageSize)
            assertEquals(original.gestureBindings, decoded.gestureBindings)
        }
    }

    @Test fun rapidSwitchesStayOrderedAndLeavingUiDoesNotCancelPendingWrites() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        val saver = QuickPanelSettingsSaver(this) { value ->
            if (writes.isEmpty()) release.await()
            writes += value
        }
        val values = listOf(true, false, true, false)
        val saves = values.map(saver::save)
        val ui = async { saves.last().await() }
        ui.cancelAndJoin()
        assertTrue(saves.none { it.isCancelled })
        release.complete(Unit)
        saves.forEachIndexed { index, saved -> assertEquals(values[index], saved.await()) }
        assertEquals(values, writes)
    }

    @Test fun aFailedWriteDoesNotBlockTheNextChoice() = runBlocking {
        val supervisor = SupervisorJob(coroutineContext[Job])
        val owner = CoroutineScope(coroutineContext + supervisor)
        var attempts = 0
        val saver = QuickPanelSettingsSaver(owner) { if (++attempts == 1) error("disk unavailable") }
        val failed = saver.save(true)
        val next = saver.save(false)
        assertTrue(runCatching { failed.await() }.isFailure)
        assertFalse(next.await())
        supervisor.cancelAndJoin()
    }

    @Test fun switchAndPanelReuseExistingUiAndDoNotExecuteTranslation() {
        assertTrue(com.gameocr.app.R.string.settings_quick_panel_enabled in SETTINGS_SEARCH_TARGET_RES_IDS)
        fun source(path: String) = File("src/main/java/com/gameocr/app/$path").readText()
        val ui = source("ui/QuickPanelSettingsSection.kt")
        val manager = source("overlay/FloatingButtonManager.kt")
        val panel = manager.substringAfter("private fun openQuickPanel() {").substringBefore("private fun openArcMenuPage(")
        assertTrue(ui.contains("SettingsChoiceRow("))
        assertTrue("An old failure must not revert a newer choice", ui.contains("if (request == revision)"))
        assertTrue(ui.contains("R.string.settings_quick_panel_title else R.string.settings_section_arc_menu"))
        assertTrue(panel.contains("onSwitchToLoop = { selectMode(FloatingSkill.LOOP) }"))
        listOf("onSingleTap()", "triggerOnce()", "toggleLoopMode()", "onDoubleTap()").forEach {
            assertFalse("Selecting a mode must not execute $it", panel.contains(it))
        }
        assertTrue(manager.contains("arcMenuView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE"))
        assertTrue(manager.contains("if (panel) showArcMenu() else openArcMenuPage(page)"))
        assertTrue(manager.contains("val reopenPanel = quickPanelOpen"))
        val gestures = source("ui/GestureSettingsEditor.kt")
        assertFalse(gestures.contains("gesture_main_function"))
        assertFalse(gestures.contains("gesture_enable"))
        assertTrue(source("ui/FloatingSettingsPages.kt").contains("BINDINGS(R.string.gesture_binding_title)"))
    }
}
