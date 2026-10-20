package com.gameocr.app.download

import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelReadinessTest {
    @Test
    fun readinessStateAndDownloadPermission_areTableDriven() {
        data class Case(
            val name: String,
            val installed: Boolean,
            val supported: Boolean,
            val expectedState: ModelReadinessState,
            val expectedReady: Boolean,
            val expectedDownloadable: Boolean,
        )

        val cases = listOf(
            Case("missing supported", false, true, ModelReadinessState.MISSING, false, true),
            Case("installed supported", true, true, ModelReadinessState.READY, true, false),
            Case("missing unsupported", false, false, ModelReadinessState.UNSUPPORTED, false, false),
            Case("installed unsupported", true, false, ModelReadinessState.UNSUPPORTED, false, false),
        )

        cases.forEach { case ->
            val readiness = ModelReadiness(
                spec = ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4),
                installed = case.installed,
                supported = case.supported,
                totalBytes = if (case.installed) 123L else 0L,
            )
            assertEquals(case.name, case.expectedState, readiness.state)
            assertEquals(case.name, case.expectedReady, readiness.ready)
            assertEquals(case.name, case.expectedDownloadable, readiness.downloadable)
        }
    }

    @Test
    fun upgradeAvailable_keepsTheModelReadyButLetsTheWorkerRun() {
        data class Case(val name: String, val installed: Boolean, val upgrade: Boolean, val complete: Boolean)

        listOf(
            Case("missing", installed = false, upgrade = false, complete = false),
            Case("installed", installed = true, upgrade = false, complete = true),
            Case("installed without the optional file", installed = true, upgrade = true, complete = false),
        ).forEach { case ->
            val readiness = ModelReadiness(
                spec = ModelDownloadSpec.mangaOcr(),
                installed = case.installed,
                supported = true,
                upgradeAvailable = case.upgrade,
            )
            assertEquals(case.name, case.installed, readiness.ready)
            assertEquals(case.name, case.complete, ModelDownloadWorkPolicy.alreadyComplete(readiness))
        }
    }
}
