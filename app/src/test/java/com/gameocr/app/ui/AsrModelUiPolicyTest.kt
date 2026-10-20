package com.gameocr.app.ui

import com.gameocr.app.asr.AsrModelId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrModelUiPolicyTest {
    @Test fun backgroundWorkOverridesStaticReadiness_tableDriven() {
        data class Case(val ready: Boolean, val failed: Boolean, val downloaded: Long?, val total: Long?, val expected: AsrModelUiState)
        listOf(
            Case(false, false, null, null, AsrModelUiState.NotDownloaded),
            Case(true, false, null, null, AsrModelUiState.Ready),
            Case(false, true, null, null, AsrModelUiState.Failed),
            Case(true, true, null, null, AsrModelUiState.Ready),
            Case(false, false, 50, 200, AsrModelUiState.Downloading(25)),
            Case(false, true, 0, 200, AsrModelUiState.Downloading(0)),
            Case(false, false, 10, null, AsrModelUiState.Downloading(null)),
            Case(false, false, 200, 200, AsrModelUiState.Downloading(100)),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected,
                AsrModelUiPolicy.state(case.ready, case.failed, case.downloaded, case.total))
        }
    }

    @Test fun importAndDeleteFollowModelState_tableDriven() {
        data class Case(val state: AsrModelUiState, val import: Boolean, val delete: Boolean)
        listOf(
            Case(AsrModelUiState.NotDownloaded, import = true, delete = false),
            Case(AsrModelUiState.Failed, import = true, delete = false),
            // Ready models use the same disabled download/import actions as OCR.
            Case(AsrModelUiState.Ready, import = false, delete = true),
            Case(AsrModelUiState.Downloading(40), import = false, delete = false),
            Case(AsrModelUiState.Downloading(null), import = false, delete = false),
            Case(AsrModelUiState.Verifying, import = false, delete = false),
            Case(AsrModelUiState.Checking, import = false, delete = false),
            Case(AsrModelUiState.Queued, import = false, delete = false),
            Case(AsrModelUiState.Importing, import = false, delete = false),
        ).forEach { case ->
            assertEquals(case.toString(), case.import, AsrModelUiPolicy.canImport(case.state))
            assertEquals(case.toString(), case.import, AsrModelUiPolicy.canDownload(case.state))
            assertEquals(case.toString(), !case.import && !case.delete, AsrModelUiPolicy.isBusy(case.state))
            assertEquals(case.toString(), case.delete, AsrModelUiPolicy.canDelete(case.state))
        }
    }

    @Test fun explicitPhasesOverrideOldFileAndReadiness_tableDriven() {
        data class Case(val busy: Boolean, val verifying: Boolean, val importing: Boolean, val checking: Boolean, val expected: AsrModelUiState)
        listOf(
            Case(true, true, false, false, AsrModelUiState.Verifying),
            Case(false, true, false, false, AsrModelUiState.Ready),
            Case(true, true, true, false, AsrModelUiState.Importing),
            Case(false, false, false, true, AsrModelUiState.Checking),
            Case(true, false, false, true, AsrModelUiState.Queued),
        ).forEach {
            assertEquals(it.toString(), it.expected, AsrModelUiPolicy.state(true, true, null, null,
                busy = it.busy, verifying = it.verifying, importing = it.importing, checking = it.checking))
        }
    }

    @Test fun sizesRoundToWholeMegabytes() {
        assertEquals(228L, AsrModelUiPolicy.sizeMegabytes(AsrModelId.SENSE_VOICE))
        assertEquals(99L, AsrModelUiPolicy.sizeMegabytes(AsrModelId.WHISPER_TINY))
    }

    @Test fun downloadProgressIsOnlyInTheSharedCard_otherModelStatusesRemain_tableDriven() {
        listOf(
            AsrModelUiState.Downloading(0) to false,
            AsrModelUiState.Downloading(25) to false,
            AsrModelUiState.Downloading(100) to false,
            AsrModelUiState.Downloading(null) to false,
            AsrModelUiState.Queued to false,
            AsrModelUiState.Verifying to false,
            AsrModelUiState.Checking to true,
            AsrModelUiState.Importing to true,
            AsrModelUiState.NotDownloaded to true,
            AsrModelUiState.Ready to true,
            AsrModelUiState.Failed to true,
        ).forEach { (state, show) -> assertEquals(state.toString(), show, AsrModelUiPolicy.showInlineStatus(state)) }

        val speech = source("AsrSettingsSection.kt").substringAfter("private fun OnDeviceModelSection(")
            .substringBefore("private fun CloudEngineSection(")
        assertTrue(speech.contains("if (AsrModelUiPolicy.showInlineStatus(modelState))"))
        assertFalse(speech.contains("ModelDownloadProgressLine("))
        assertFalse(speech.contains("settings_asr_cancel_download"))
        assertFalse(speech.contains("cancelModelDownload("))
        assertTrue(speech.contains("enabled = AsrModelUiPolicy.canDelete(modelState)"))
        assertTrue(speech.contains("R.string.settings_paddle_btn_delete"))
        val shared = source("SettingsScreen.kt").substringAfter("val modelDownloadFeedback:")
            .substringBefore("LaunchedEffect(translatorEngine, localModelId,")
        assertTrue(shared.contains("ModelDownloadProgressCard("))
        assertTrue(shared.contains("viewModel.cancelModelDownload(download.id)"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/java/com/gameocr/app/ui/$name"), File("app/src/main/java/com/gameocr/app/ui/$name"),
    ).first(File::isFile).readText()
}
