package com.gameocr.app.network

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ScreenWakeLogBoundaryTest {
    @Test fun normalWakeRecoveryOnlyGoesToLogcat() {
        val source = File("src/main/java/com/gameocr/app/network/ScreenWakeNetworkRecovery.kt").readText()
        assertFalse(source.contains("LogRepository"))
        listOf(
            "httpClient.connectionPool.evictAll()",
            "performanceDiagnostics.onScreenWake(",
            "Timber.tag(NETWORK_PERF_TAG).i(message)",
            "[network-wake] cleared idle cloud connections=",
        ).forEach { assertTrue(it, source.contains(it)) }
    }
}
