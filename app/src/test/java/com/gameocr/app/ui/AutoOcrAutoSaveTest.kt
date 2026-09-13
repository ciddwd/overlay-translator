package com.gameocr.app.ui

import com.gameocr.app.data.AutoOcrLanguageListPolicy
import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrSettings
import com.gameocr.app.data.OcrEngineKind
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoOcrAutoSaveTest {
    @Test fun switchAddRemovePersistInOrderAndLeavingUiDoesNotCancelSave() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<AutoOcrSettings>()
        val saver = AutoOcrSettingsSaver(this) {
            if (writes.isEmpty()) release.await()
            writes += it
        }
        val switched = AutoOcrSettings(routes = mapOf("ja" to AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA)))
        val added = AutoOcrLanguageListPolicy.add(switched, "fr")
        val mapped = added.copy(routes = added.routes + ("fr" to AutoOcrRoute(OcrEngineKind.ML_KIT_LATIN)))
        val values = listOf(switched, added, mapped, AutoOcrLanguageListPolicy.remove(mapped, "fr"))
        val saves = values.map(saver::save)
        val uiAwaiter = async { saves.last().await() }
        uiAwaiter.cancelAndJoin()
        assertTrue(saves.none { it.isCancelled })
        release.complete(Unit)
        saves.forEach { it.await() }
        assertEquals(values.map { it.normalized() }, writes)
        assertEquals(switched, writes.last())
    }

    @Test fun aliasesAndEmptyMappingsAreNormalized_tableDriven() = runBlocking {
        val cases = listOf(
            AutoOcrSettings(),
            AutoOcrSettings(routes = mapOf(" JA " to AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA))),
            AutoOcrSettings(additionalLanguages = listOf("fr", "FR", "iw", "auto", "und")),
            AutoOcrSettings(routes = mapOf("en" to AutoOcrRoute(OcrEngineKind.ML_KIT_AUTO))),
        )
        val writes = mutableListOf<AutoOcrSettings>()
        val saver = AutoOcrSettingsSaver(this) { writes += it }
        cases.forEach { assertEquals(it.normalized(), saver.save(it).await()) }
        assertEquals(cases.map { it.normalized() }, writes)
    }

    @Test fun failedWriteDoesNotPreventNextWrite() = runBlocking {
        val supervisor = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
        val scope = CoroutineScope(coroutineContext + supervisor)
        var attempts = 0
        val saver = AutoOcrSettingsSaver(scope) { if (++attempts == 1) error("disk unavailable") }
        val failed = saver.save(AutoOcrSettings())
        val expected = AutoOcrLanguageListPolicy.add(AutoOcrSettings(), "fr")
        val next = saver.save(expected)
        assertTrue(runCatching { failed.await() }.isFailure)
        assertEquals(expected, next.await())
        supervisor.cancelAndJoin()
    }

    @Test fun uiOnlyPatchesAutoOcrAndSaveOutlivesPage() {
        val ui = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val helper = ui.substringAfter("fun autoSaveAutoOcrSettings(").substringBefore("val doSave:")
        assertTrue(helper.contains("if (initialSettings == null) return"))
        assertTrue(helper.contains("val value = update(autoOcr).normalized()"))
        assertTrue(helper.contains("initialSettings = initialSettings?.copy(autoOcr = saved)"))
        assertFalse(helper.contains("initialSettings = buildSnapshot()"))
        assertTrue(helper.indexOf("viewModel.saveAutoOcrSettings") < helper.indexOf("scope.launch"))
        assertTrue(helper.contains("revision > autoOcrSavedRevision"))
        assertTrue(helper.contains("revision == autoOcrSaveRevision"))
        val vm = File("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()
        assertTrue(vm.contains("AutoOcrSettingsSaver(viewModelScope)"))
        assertTrue(vm.contains("repo.update { it.copy(autoOcr = value) }"))
    }
}
