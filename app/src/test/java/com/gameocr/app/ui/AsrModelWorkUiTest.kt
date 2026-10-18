package com.gameocr.app.ui

import androidx.work.Data
import androidx.work.WorkInfo
import com.gameocr.app.asr.AsrModelId
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelDownloadWorkPolicy
import com.gameocr.app.download.ModelDownloadWorker
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AsrModelWorkUiTest {
    private val spec = ModelDownloadSpec.asr(AsrModelId.WHISPER_BASE)
    private val other = ModelDownloadSpec.asr(AsrModelId.SENSE_VOICE)
    private fun job(state: WorkInfo.State, created: Long, selected: ModelDownloadSpec = spec,
        progress: Data = Data.EMPTY, output: Data = Data.EMPTY) = WorkInfo(
        UUID.randomUUID(), state,
        setOf(ModelDownloadWorkPolicy.specTag(selected), ModelDownloadWorkPolicy.createdTag(created)),
        outputData = output, progress = progress)

    @Test fun queuedAndTerminalStatesRestoreWithoutProgress_tableDriven() {
        WorkInfo.State.entries.forEach { state ->
            val work = job(state, 10)
            assertEquals(state.name, work, AsrModelWorkUi.latest(listOf(work), spec, null))
            assertFalse(AsrModelWorkUi.ownProgress(work, spec))
        }
    }

    @Test fun latestAttemptSupersedesPreviousFailureAndIgnoresOtherModels() {
        val failed = job(WorkInfo.State.FAILED, 10)
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.CANCELLED,
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED).forEach { state ->
            val latest = job(state, 20)
            val unrelated = job(WorkInfo.State.RUNNING, 30, other)
            assertEquals(state.name, latest, AsrModelWorkUi.latest(listOf(latest, unrelated, failed), spec, failed.id))
        }
        assertNull(AsrModelWorkUi.latest(listOf(job(WorkInfo.State.RUNNING, 10, other)), spec, null))
    }

    @Test fun batchProgressDoesNotLeakFromAnotherModel() {
        listOf(spec, other).forEach { current ->
            val progress = Data.Builder().putString(ModelDownloadWorker.KEY_CURRENT_SPEC, current.encode())
                .putStringArray(ModelDownloadWorker.KEY_SPECS, arrayOf(spec.encode(), other.encode())).build()
            val work = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet(), progress = progress)
            assertEquals(work, AsrModelWorkUi.latest(listOf(work), spec, null))
            assertEquals(current == spec, AsrModelWorkUi.ownProgress(work, spec))
        }
    }

    @Test fun oldFailureRestoresFromPersistedOutputWithoutNewTags() {
        val output = Data.Builder().putString(ModelDownloadWorker.KEY_CURRENT_SPEC, spec.encode())
            .putString(ModelDownloadWorker.KEY_STATUS, "failure detail")
            .putLong(ModelDownloadWorker.KEY_FINISHED_AT, 10).build()
        val failed = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet(), outputData = output)
        assertEquals(failed, AsrModelWorkUi.latest(listOf(failed), spec, null))
        assertEquals("failure detail", AsrModelWorkUi.latest(listOf(failed), spec, null)?.outputData?.getString(ModelDownloadWorker.KEY_STATUS))
    }
}
