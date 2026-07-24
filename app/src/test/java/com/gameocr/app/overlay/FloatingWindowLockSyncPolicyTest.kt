package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowLockSyncPolicyTest {

    @Test
    fun resolveFloatingWindowLockSync_tableDriven_ignoresStaleSnapshots() {
        data class Case(
            val name: String,
            val currentLocked: Boolean,
            val settingsLocked: Boolean,
            val syncSettingsState: Boolean,
            val pendingLocked: Boolean?,
            val expectedLocked: Boolean,
            val expectedPending: Boolean?,
        )

        listOf(
            Case("settings flow locks", false, true, true, null, true, null),
            Case("settings flow unlocks", true, false, true, null, false, null),
            Case("stale capture cannot unlock", true, false, false, null, true, null),
            Case("stale capture cannot lock", false, true, false, null, false, null),
            Case("stale flow cannot override pending lock", true, false, true, true, true, true),
            Case("matching flow confirms pending lock", true, true, true, true, true, null),
            Case("stale flow cannot override pending unlock", false, true, true, false, false, false),
            Case("matching flow confirms pending unlock", false, false, true, false, false, null),
        ).forEach { case ->
            val actual = resolveFloatingWindowLockSync(
                currentLocked = case.currentLocked,
                settingsLocked = case.settingsLocked,
                syncSettingsState = case.syncSettingsState,
                pendingLocked = case.pendingLocked,
            )
            assertEquals(
                "${case.name} locked",
                case.expectedLocked,
                actual.locked,
            )
            assertEquals("${case.name} pending", case.expectedPending, actual.pendingLocked)
        }
    }

    @Test
    fun captureService_tableDriven_marksLockSettingAuthority() {
        data class Case(
            val name: String,
            val expectedCall: String,
        )

        val source = sourceFile(
            "app/src/main/java/com/gameocr/app/service/CaptureService.kt",
            "src/main/java/com/gameocr/app/service/CaptureService.kt",
        ).readText()
        listOf(
            Case(
                "repository flow is authoritative",
                "applyOverlayConfig(s, syncFloatingWindowLock = true)",
            ),
            Case(
                "capture snapshot is non-authoritative",
                "applyOverlayConfig(settings, syncFloatingWindowLock = false)",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.expectedCall in source)
        }
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Source file not found: ${candidates.joinToString()}")
}
