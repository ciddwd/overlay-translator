package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FloatingButtonAutoSaveTest {
    private val defaults = FloatingButtonSettings(40, 1f, true, true, 10)

    @Test fun boundsAndIndependentSwitches_tableDriven() {
        data class Case(val input: FloatingButtonSettings, val expected: FloatingButtonSettings)
        listOf(
            Case(defaults, defaults),
            Case(defaults.copy(sizeDp = 0, alpha = -1f, dockInsetDp = -3), defaults.copy(sizeDp = 32, alpha = 0.1f, dockInsetDp = 0)),
            Case(defaults.copy(sizeDp = 200, alpha = 2f, dockInsetDp = 90), defaults.copy(sizeDp = 96, alpha = 1f, dockInsetDp = 40)),
            Case(defaults.copy(alpha = Float.NaN), defaults),
            Case(defaults.copy(alpha = Float.POSITIVE_INFINITY), defaults),
            Case(defaults.copy(snapToEdge = false), defaults.copy(snapToEdge = false)),
            Case(defaults.copy(autoDock = false), defaults.copy(autoDock = false)),
        ).forEach { assertEquals(it.expected, it.input.normalized()) }
    }

    @Test fun savedFieldsOnlyPatchDirtyBaselineAndLatestStorage() {
        val initial = Settings(promptTemplate = "saved", floatingButtonX = 125, floatingButtonY = 230)
        val draft = initial.copy(promptTemplate = "unsaved")
        val selected = FloatingButtonSettings(64, 0.45f, false, true, 22)
        val baseline = selected.applyTo(initial)
        val currentDraft = selected.applyTo(draft)
        assertNotEquals(baseline, currentDraft)
        assertEquals("saved", baseline.promptTemplate)
        assertEquals("unsaved", currentDraft.promptTemplate)
        assertEquals(125, baseline.floatingButtonX)
        assertEquals(230, baseline.floatingButtonY)
        assertEquals(currentDraft, baseline.copy(promptTemplate = "unsaved"))
        assertEquals("concurrent edit", selected.applyTo(initial.copy(promptTemplate = "concurrent edit")).promptTemplate)
    }

    @Test fun rapidEventsPersistInOrderAndLeavingUiDoesNotCancelSave() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<FloatingButtonSettings>()
        val saver = FloatingButtonSettingsSaver(this) { value ->
            if (writes.isEmpty()) release.await()
            writes += value
        }
        val values = listOf(defaults, defaults.copy(alpha = 0.5f), defaults.copy(alpha = 0.5f, snapToEdge = false))
        val saves = values.map(saver::save)
        val uiAwaiter = async { saves.last().await() }
        uiAwaiter.cancelAndJoin()
        assertTrue(saves.none { it.isCancelled })
        release.complete(Unit)
        saves.forEach { it.await() }
        assertEquals(values, writes)
    }

    @Test fun failedWriteDoesNotPreventNextWrite() = runBlocking {
        val supervisor = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        val scope = CoroutineScope(coroutineContext + supervisor)
        var attempts = 0
        val saver = FloatingButtonSettingsSaver(scope) { if (++attempts == 1) error("disk unavailable") }
        val failed = saver.save(defaults)
        val next = saver.save(defaults.copy(alpha = 0.4f))
        assertTrue(runCatching { failed.await() }.isFailure)
        assertEquals(0.4f, next.await().alpha, 0f)
        supervisor.cancelAndJoin()
    }

    @Test fun uiCommitsSlidersOnReleaseAndBothSwitchesImmediately() {
        val ui = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val card = ui.substringAfter("item(key = SectionKeys.FLOATING)").substringBefore("item(key = SectionKeys.ARC_MENU)")
        assertEquals(3, Regex("onValueChangeFinished = ::autoSaveFloatingButtonSettings").findAll(card).count())
        assertEquals(2, Regex("autoSaveFloatingButtonSettings\\(\\)").findAll(card).count())
        val helper = ui.substringAfter("fun autoSaveFloatingButtonSettings()").substringBefore("var showUnsavedDialog")
        assertTrue(helper.contains("if (initialSettings == null) return"))
        assertTrue(helper.contains("initialSettings = initialSettings?.let(saved::applyTo)"))
        assertFalse(helper.contains("initialSettings = buildSnapshot()"))
        assertTrue(helper.indexOf("viewModel.saveFloatingButtonSettings") < helper.indexOf("scope.launch"))
    }
}
