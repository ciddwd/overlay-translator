package com.gameocr.app.download

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadNetworkPolicyTest {
    @Test
    fun settingsNetworkProbe_onlyChecksManagedModelSources_tableDriven() {
        val source = java.io.File("src/main/java/com/gameocr/app/download/ModelDownloadNetworkTester.kt").readText()
        val configuredProbe = source.substringAfter("internal suspend fun probeConfiguredSources()")
            .substringBefore("private suspend fun sourceUrl")
        val targets = Regex("ModelDownloadNetworkTarget\\.(\\w+) to").findAll(configuredProbe)
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("LOCAL_LLM", "PADDLE_OCR", "MANGA_OCR"), targets)
        listOf(
            "llmInstaller.downloadProbeUrl(LlmModelKind.SAKURA_1_5B_Q4)",
            "paddleInstaller.downloadProbeUrl(settings.paddleModelVersion)",
            "mangaOcrInstaller.downloadProbeUrl()",
        ).forEach { assertTrue(it, configuredProbe.contains(it)) }
        listOf("GOOGLE_ML_KIT", "GOOGLE_CONNECTIVITY_URL", "https://www.google.com/").forEach {
            assertFalse(it, source.contains(it))
        }
    }

    @Test
    fun settingsNetworkProbe_usesApprovedHintWithoutMlKitResultRow() {
        val ui = java.io.File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        assertTrue(ui.contains("stringResource(R.string.settings_model_network_test_hint)"))
        assertFalse(ui.contains("settings_model_network_target_mlkit"))
        listOf(
            "values-zh-rCN" to "检查当前模型下载源是否可用",
            "values" to "Checks whether the current model download sources are available.",
        ).forEach { (locale, expected) ->
            val strings = java.io.File("src/main/res/$locale/strings.xml").readText()
            val hints = Regex("<string name=\"settings_model_network_test_hint\">([^<]*)</string>")
                .findAll(strings).map { it.groupValues[1] }.toList()
            assertEquals(locale, listOf(expected), hints)
            assertFalse(locale, strings.contains("settings_model_network_target_mlkit"))
        }
    }

    @Test
    fun acceptsHttpCode_onlyAcceptsSuccessfulResponses() {
        val cases = listOf(
            199 to false,
            200 to true,
            206 to true,
            299 to true,
            300 to false,
            404 to false,
            500 to false,
        )

        cases.forEach { (code, expected) ->
            assertEquals("HTTP $code", expected, ModelDownloadNetworkPolicy.acceptsHttpCode(code))
        }
    }

    @Test
    fun classifyFailure_reportsActionableNetworkStage() {
        val cases = listOf(
            UnknownHostException("dns") to ModelDownloadNetworkFailureKind.DNS,
            SSLException("tls") to ModelDownloadNetworkFailureKind.TLS,
            SocketTimeoutException("timeout") to ModelDownloadNetworkFailureKind.TIMEOUT,
            ConnectException("connect") to ModelDownloadNetworkFailureKind.CONNECTION,
            IllegalArgumentException("url") to ModelDownloadNetworkFailureKind.INVALID_SOURCE,
            RuntimeException("other") to ModelDownloadNetworkFailureKind.UNKNOWN,
        )

        cases.forEach { (error, expected) ->
            assertEquals(error.javaClass.simpleName, expected, ModelDownloadNetworkPolicy.classifyFailure(error))
        }
    }
}
