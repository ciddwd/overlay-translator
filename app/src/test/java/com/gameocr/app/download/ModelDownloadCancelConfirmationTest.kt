package com.gameocr.app.download

import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadCancelConfirmationTest {
    @Test
    fun actions_tableDriven_onlyConfirmationCancelsTheSameRequestOnce() {
        data class Case(val action: String, val current: String?, val shouldCancel: Boolean)
        listOf(
            Case("confirm", "first", true),
            Case("cancel button", "first", false),
            Case("back", "first", false),
            Case("outside", "first", false),
            Case("confirm", null, false), // finished, failed, timeout or removed
            Case("confirm", "replacement", false),
        ).forEach { case ->
            val confirmation = ModelDownloadCancelConfirmation()
            assertNull(confirmation.request.value)
            assertFalse(confirmation.consume(case.current))
            confirmation.show("first")
            assertEquals("opening a dialog does not cancel", "first", confirmation.request.value)
            if (case.action != "confirm") confirmation.dismiss()
            assertEquals(case.toString(), case.shouldCancel, confirmation.consume(case.current))
            assertNull(confirmation.request.value)
            assertFalse("double confirm ignored", confirmation.consume(case.current))
        }
    }

    @Test
    fun workStates_tableDriven_onlyUnfinishedModelJobsAreCancellable() {
        assertFalse(canCancelModelDownload(null))
        WorkInfo.State.entries.forEach { state ->
            listOf(true, false).forEach { modelJob ->
                val tags = if (modelJob) setOf(ModelDownloadWorkPolicy.WORK_TAG) else setOf("gallery")
                val info = WorkInfo(UUID.randomUUID(), state, tags)
                assertEquals("$state model=$modelJob", modelJob && !state.isFinished, canCancelModelDownload(info))
            }
        }
    }
}
