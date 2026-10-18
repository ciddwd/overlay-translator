package com.gameocr.app.ocr

import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MlKitOcrModuleStateTest {
    @Test fun terminalStates_tableDriven_requestAndProgressAreNotCompletion() = runBlocking {
        for (terminal in listOf(InstallState.STATE_COMPLETED, InstallState.STATE_FAILED, InstallState.STATE_CANCELED)) {
            val updates = Channel<MlKitOcrInstallUpdate>(Channel.UNLIMITED)
            val result = async { runCatching { awaitMlKitOcrModule(7, false, updates) } }
            yield()
            assertFalse(result.isCompleted)
            updates.send(MlKitOcrInstallUpdate(8, terminal, 13))
            updates.send(MlKitOcrInstallUpdate(7, InstallState.STATE_DOWNLOADING))
            yield()
            assertFalse(result.isCompleted)
            updates.send(MlKitOcrInstallUpdate(7, terminal, 13))
            val finished = withTimeout(1_000) { result.await() }
            when (terminal) {
                InstallState.STATE_COMPLETED -> assertTrue(finished.isSuccess)
                InstallState.STATE_FAILED -> assertEquals("Google Play services: 13", finished.exceptionOrNull()?.message)
                else -> assertTrue(finished.exceptionOrNull() is CancellationException)
            }
            updates.close()
        }
    }

    @Test fun installedModuleDoesNotWaitForANewSession() = runBlocking {
        val updates = Channel<MlKitOcrInstallUpdate>()
        withTimeout(1_000) { awaitMlKitOcrModule(0, true, updates) }
        updates.close()
        Unit
    }
}
