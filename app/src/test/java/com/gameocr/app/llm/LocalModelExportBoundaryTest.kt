package com.gameocr.app.llm

import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsFieldPolicy
import com.gameocr.app.data.TranslatorEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelExportBoundaryTest {
    @Test
    fun localModelIdentityAndRuntimeConfigurationNeverExport_tableDriven() {
        for (id in listOf(null, "imported:device-private-weight")) {
            val settings = Settings(
                translatorEngine = TranslatorEngine.LOCAL_QWEN3,
                localModelId = id,
                runtimeLocalModelName = "Device private name",
                runtimeLocalModelConfig = LocalModelConfig(translationPrompt = "Private instructions"),
            )
            val encoded = Json.encodeToString(settings)
            for (excluded in listOf("localModelId", "runtimeLocalModelName", "runtimeLocalModelConfig",
                "device-private-weight", "Device private name", "Private instructions")) {
                assertFalse(excluded, encoded.contains(excluded))
            }
            val decoded = Json.decodeFromString<Settings>(encoded)
            assertNull(decoded.localModelId)
            assertNull(decoded.runtimeLocalModelConfig)
            val merged = SettingsFieldPolicy.applyPortable(settings, Settings())
            assertEquals(settings.localModelId, merged.localModelId)
            assertEquals(settings.runtimeLocalModelConfig, merged.runtimeLocalModelConfig)
        }
    }
}
