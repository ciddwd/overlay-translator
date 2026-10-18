package com.gameocr.app.asr

import com.gameocr.app.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SharedTencentCredentialsTest {
    @Test fun migrationNeverOverwritesOrMixesExistingSharedCredentials_tableDriven() {
        val legacy = """{"tencentSecretId":"legacy-id","tencentSecretKey":"legacy-key"}"""
        listOf("shared-id" to "shared-key", "shared-id" to "", "" to "shared-key").forEach { pair ->
            assertEquals(pair, SharedTencentCredentials.resolve(pair.first, pair.second, legacy))
        }
        assertEquals("legacy-id" to "legacy-key", SharedTencentCredentials.resolve("", "", legacy))
        listOf("", "not-json", "[]", """{"tencentSecretId":"only-id"}""",
            """{"tencentSecretId":123,"tencentSecretKey":"key"}""").forEach { invalid ->
            assertEquals("" to "", SharedTencentCredentials.resolve("", "", invalid))
        }
        val encoded = Json.encodeToString(AsrCloudConnections(tencentAppId = "123456"))
        assertFalse(encoded.contains("tencentSecretId"))
        assertFalse(encoded.contains("tencentSecretKey"))
    }

    @Test fun sharedKeysRestartOnlyTencentSpeech() {
        AsrCloudProvider.entries.forEach { provider ->
            val settings = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD,
                asrCloudProvider = provider, tencentSecretId = "id", tencentSecretKey = "key")
            val before = SpeechStartPolicy.engineKey(settings)
            val after = SpeechStartPolicy.engineKey(settings.copy(tencentSecretKey = "new-key"))
            assertEquals(provider.name, provider == AsrCloudProvider.TENCENT, before != after)
        }
    }

    @Test fun onlyV2ResourcesAreSelectableAndLegacySelectionsKeepBillingMode_tableDriven() {
        assertEquals(listOf(VolcAsrResource.V2_DURATION, VolcAsrResource.V2_CONCURRENT), VolcAsrResource.entries)
        mapOf("V1_DURATION" to VolcAsrResource.V2_DURATION,
            "V1_CONCURRENT" to VolcAsrResource.V2_CONCURRENT,
            "V2_DURATION" to VolcAsrResource.V2_DURATION,
            "V2_CONCURRENT" to VolcAsrResource.V2_CONCURRENT).forEach { (stored, expected) ->
            val config = Json.decodeFromString<AsrCloudConnections>("""{"volcResource":"$stored","tencentAppId":"123456"}""")
            assertEquals(expected, config.volcResource)
            assertEquals("123456", config.tencentAppId)
            assertTrue(Json.encodeToString(config).let { it.contains(expected.name) || expected == VolcAsrResource.V2_DURATION })
            assertTrue(config.volcResource.resourceId.startsWith("volc.seedasr."))
        }
        assertTrue(runCatching { Json.decodeFromString<AsrCloudConnections>("""{"volcResource":"INVALID"}""") }.isFailure)
    }
}
