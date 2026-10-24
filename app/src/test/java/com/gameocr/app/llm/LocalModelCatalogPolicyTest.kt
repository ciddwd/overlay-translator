package com.gameocr.app.llm

import java.util.UUID
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogPolicyTest {
    @Test
    fun configNormalization_coversBoundsAndNonFiniteOverrides() {
        data class Case(val input: LocalModelConfig, val expected: LocalModelConfig)
        listOf(
            Case(LocalModelConfig(), LocalModelConfig()),
            Case(LocalModelConfig(contextTokens = -1, maxNewTokens = 0), LocalModelConfig(512, 16)),
            Case(LocalModelConfig(contextTokens = 9000, maxNewTokens = 9000), LocalModelConfig(4096, 4096)),
            Case(LocalModelConfig(translationPrompt = "  Translate only. \n"), LocalModelConfig(translationPrompt = "Translate only.")),
            Case(LocalModelConfig(temperature = Float.NaN, topP = Float.POSITIVE_INFINITY,
                repeatPenalty = Float.NEGATIVE_INFINITY), LocalModelConfig()),
            Case(LocalModelConfig(temperature = -1f, topP = -1f, topK = -1, repeatPenalty = -1f),
                LocalModelConfig(temperature = 0f, topP = 0.01f, topK = 0, repeatPenalty = 0.5f)),
            Case(LocalModelConfig(temperature = 8f, topP = 9f, topK = 1000, repeatPenalty = 10f),
                LocalModelConfig(temperature = 2f, topP = 1f, topK = 200, repeatPenalty = 2f)),
            Case(LocalModelConfig(generationThreads = 0, promptThreads = -3), LocalModelConfig()),
            Case(LocalModelConfig(generationThreads = 65, promptThreads = 999),
                LocalModelConfig(generationThreads = 64, promptThreads = 64)),
            Case(LocalModelConfig(batchTokens = 0, microBatchTokens = -1),
                LocalModelConfig(batchTokens = 32, microBatchTokens = 32)),
            Case(LocalModelConfig(contextTokens = 512, batchTokens = 2048, microBatchTokens = 2048),
                LocalModelConfig(contextTokens = 512)),
            Case(LocalModelConfig(batchTokens = 9999, microBatchTokens = 9999),
                LocalModelConfig(batchTokens = 2048, microBatchTokens = 2048)),
            Case(LocalModelConfig(batchTokens = 64, microBatchTokens = 512),
                LocalModelConfig(batchTokens = 64, microBatchTokens = 64)),
            Case(LocalModelConfig(cacheTypeK = " Q8_0 ", cacheTypeV = " Q4_0 ", flashAttention = " ON "),
                LocalModelConfig(cacheTypeK = "q8_0", cacheTypeV = "q4_0", flashAttention = "on")),
            Case(LocalModelConfig(cacheTypeK = "unknown", cacheTypeV = "unknown", flashAttention = "unknown"),
                LocalModelConfig()),
            Case(LocalModelConfig(cacheTypeK = "q4_0", cacheTypeV = "q8_0", flashAttention = "off"),
                LocalModelConfig(cacheTypeK = "q4_0", flashAttention = "off")),
            Case(LocalModelConfig(gpuLayers = -9, idleUnloadMinutes = -1),
                LocalModelConfig(gpuLayers = -1, idleUnloadMinutes = 0)),
            Case(LocalModelConfig(gpuLayers = 1000, idleUnloadMinutes = 90),
                LocalModelConfig(gpuLayers = 999, idleUnloadMinutes = 60)),
        ).forEach { case ->
            assertEquals(case.expected, case.input.normalized())
            assertEquals(case.expected, case.input.normalized().normalized())
        }
    }

    @Test
    fun fingerprint_coversEveryConfigFieldAndCanonicalNormalization() {
        val baseline = LocalModelConfig()
        listOf(
            baseline.copy(contextTokens = 1024), baseline.copy(maxNewTokens = 512),
            baseline.copy(translationPrompt = "Only the translation."), baseline.copy(temperature = 0f),
            baseline.copy(topP = 0.8f), baseline.copy(topK = 20), baseline.copy(repeatPenalty = 1.1f),
            baseline.copy(generationThreads = 1), baseline.copy(promptThreads = 1),
            baseline.copy(batchTokens = 256, microBatchTokens = 256), baseline.copy(microBatchTokens = 64),
            baseline.copy(loadMode = LocalModelLoadMode.NONE), baseline.copy(loadMode = LocalModelLoadMode.MMAP_MLOCK), baseline.copy(cacheTypeK = "q8_0"),
            baseline.copy(cacheTypeV = "q4_0"), baseline.copy(flashAttention = "off"),
            baseline.copy(gpuLayers = -1), baseline.copy(idleUnloadMinutes = 0),
        ).forEach { assertNotEquals(baseline.fingerprint(), it.fingerprint()) }
        assertEquals(baseline.fingerprint(), baseline.copy(translationPrompt = " \n ").fingerprint())
        assertEquals(baseline.fingerprint(), baseline.copy(temperature = Float.NaN).fingerprint())
        assertEquals(64, baseline.fingerprint().length)
    }

    @Test
    fun initializationFingerprint_changesOnlyNativeAllocationFields() {
        val baseline = LocalModelConfig()
        listOf(
            baseline.copy(contextTokens = 4096), baseline.copy(batchTokens = 256),
            baseline.copy(microBatchTokens = 64), baseline.copy(loadMode = LocalModelLoadMode.NONE), baseline.copy(loadMode = LocalModelLoadMode.MMAP_MLOCK),
            baseline.copy(cacheTypeK = "q8_0"), baseline.copy(cacheTypeV = "q4_0"),
            baseline.copy(flashAttention = "off"), baseline.copy(gpuLayers = 1),
        ).forEach { assertNotEquals(baseline.initializationFingerprint(), it.initializationFingerprint()) }
        listOf(
            baseline.copy(maxNewTokens = 512), baseline.copy(translationPrompt = "Only translate."),
            baseline.copy(temperature = 0.5f), baseline.copy(topP = 0.8f), baseline.copy(topK = 20),
            baseline.copy(repeatPenalty = 1.1f), baseline.copy(generationThreads = 4),
            baseline.copy(promptThreads = 8), baseline.copy(idleUnloadMinutes = 0),
        ).forEach { assertEquals(baseline.initializationFingerprint(), it.initializationFingerprint()) }
        assertEquals(64, baseline.initializationFingerprint().length)
        assertEquals(baseline.initializationFingerprint(), baseline.copy(cacheTypeK = "invalid").initializationFingerprint())
    }

    @Test
    fun configSerialization_restoresOldDefaultsAndAllIndependentNewParameters() {
        val json = Json { encodeDefaults = true }
        val previous = json.decodeFromString<LocalModelConfig>("""{"contextTokens":1024,"maxNewTokens":768}""")
        assertEquals(LocalModelConfig(contextTokens = 1024, maxNewTokens = 768), previous)
        val config = LocalModelConfig(
            generationThreads = 4, promptThreads = 8, batchTokens = 256, microBatchTokens = 64,
            loadMode = LocalModelLoadMode.MLOCK, cacheTypeK = "q8_0", cacheTypeV = "q4_0", flashAttention = "on",
            gpuLayers = -1, idleUnloadMinutes = 0,
        )
        assertEquals(config, json.decodeFromString<LocalModelConfig>(json.encodeToString(config)))
    }

    @Test
    fun configSerialization_migratesFormerMmapAndMlockSwitches() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        listOf(
            """{"useMmap":true,"useMlock":false}""" to LocalModelLoadMode.AUTO,
            """{"useMmap":true,"useMlock":true}""" to LocalModelLoadMode.MMAP_MLOCK,
            """{"useMmap":false,"useMlock":true}""" to LocalModelLoadMode.MLOCK,
            """{"useMmap":false,"useMlock":false}""" to LocalModelLoadMode.NONE,
            """{"loadMode":"dio","useMmap":null,"useMlock":null}""" to LocalModelLoadMode.DIRECT_IO,
        ).forEach { (stored, mode) ->
            val migrated = json.decodeFromString<LocalModelConfig>(stored).normalized()
            assertEquals(stored, LocalModelConfig(loadMode = mode), migrated)
            assertEquals(stored, migrated, json.decodeFromString<LocalModelConfig>(json.encodeToString(migrated)))
        }
    }

    @Test
    fun importedIdentities_cannotEscapeDirectoryAndDuplicateNamesCoexist() {
        val first = "imported:${UUID.randomUUID()}"
        val second = "imported:${UUID.randomUUID()}"
        assertNotEquals(LocalModelCatalogPolicy.importedDirectoryName(first),
            LocalModelCatalogPolicy.importedDirectoryName(second))
        listOf("../model", "imported:../../model", "imported:/model", "imported:", "builtin:QWEN",
            "imported:00000000-0000-0000-0000-000000000000/other", "imported:1-1-1-1-1").forEach { id ->
            assertTrue(id, runCatching { LocalModelCatalogPolicy.importedDirectoryName(id) }.isFailure)
        }
        val uuid = "00000000-0000-0000-0000-000000000000"
        assertEquals(uuid, LocalModelCatalogPolicy.importedDirectoryName("imported:$uuid"))
        assertEquals("model", LocalModelCatalogPolicy.displayName("folder/model.gguf"))
        assertEquals("model", LocalModelCatalogPolicy.displayName("C:\\folder\\model.GGUF"))
        assertEquals("GGUF", LocalModelCatalogPolicy.displayName(".gguf"))
    }

    @Test
    fun builtinIdentityAndQuantization_followPinnedEnum() {
        assertEquals(LlmModelKind.entries.size, LlmModelKind.entries.map { LocalModelCatalog.builtInId(it) }.distinct().size)
        listOf("7" to "Q8_0", "15" to "Q4_K_M", "16" to "Q5_K_S", "32" to "BF16", "unknown" to "unknown")
            .forEach { (raw, label) -> assertEquals(label, LocalModelCatalogPolicy.quantization(raw)) }
    }

    @Test
    fun initialConfigs_areSeededOnceAndStayIndependentAfterGlobalChanges() {
        val builtinIds = LlmModelKind.entries.map { LocalModelCatalog.builtInId(it) }
        val firstDefaults = LocalModelConfig(contextTokens = 1024, maxNewTokens = 768)
        val initial = LocalModelCatalogPolicy.seedConfigs(emptyMap(), builtinIds, firstDefaults)
        assertEquals(builtinIds.toSet(), initial.keys)
        builtinIds.forEach { assertEquals(firstDefaults, initial.getValue(it)) }
        val customConfig = firstDefaults.copy(maxNewTokens = 1024, translationPrompt = "Only translate.")
        val changed = initial + (builtinIds.first() to customConfig)
        val laterGlobals = LocalModelConfig(contextTokens = 4096, maxNewTokens = 16)
        val reloaded = LocalModelCatalogPolicy.seedConfigs(changed, builtinIds, laterGlobals)
        assertEquals(customConfig, reloaded.getValue(builtinIds.first()))
        builtinIds.drop(1).forEach { assertEquals(firstDefaults, reloaded.getValue(it)) }
        val importedId = "imported:00000000-0000-0000-0000-000000000000"
        val withImport = LocalModelCatalogPolicy.seedConfigs(reloaded, builtinIds + importedId, laterGlobals)
        assertEquals(laterGlobals, withImport.getValue(importedId))
        builtinIds.forEach { assertEquals(reloaded.getValue(it), withImport.getValue(it)) }
    }

    @Test
    fun knownIds_acceptOnlyExistingBuiltinOrImportedIdentity() {
        val known = listOf(LocalModelCatalog.builtInId(LlmModelKind.QWEN3_0_6B_Q8_0),
            "imported:00000000-0000-0000-0000-000000000000")
        data class Case(val id: String, val expected: Boolean)
        listOf(
            Case(known.first(), true), Case(known.last(), true), Case("", false),
            Case("builtin:missing", false), Case("Qwen3-0.6B", false), Case("../model.gguf", false),
            Case("imported:11111111-1111-1111-1111-111111111111", false),
        ).forEach { assertEquals(it.id, it.expected, LocalModelCatalogPolicy.isKnownId(it.id, known)) }
    }

    @Test
    fun recentUse_movesToFrontDeduplicatesFiltersMissingAndBoundsHistory() {
        val known = (1..15).map { "model-$it" }
        data class Case(val id: String, val history: List<String>, val expected: List<String>)
        listOf(
            Case("model-1", emptyList(), listOf("model-1")),
            Case("model-3", listOf("model-1", "model-2"), listOf("model-3", "model-1", "model-2")),
            Case("model-2", listOf("model-1", "model-2", "model-2", "missing"), listOf("model-2", "model-1")),
            Case("model-15", known, listOf("model-15") + known.take(11)),
        ).forEach { case ->
            assertEquals(case.expected, LocalModelCatalogPolicy.recordUse(case.id, case.history, known))
        }
        assertTrue(runCatching { LocalModelCatalogPolicy.recordUse("missing", known, known) }.isFailure)
        assertEquals(listOf("model-2", "model-1"),
            LocalModelCatalogPolicy.recentIds(listOf("missing", "model-2", "model-2", "model-1"), known))
    }

    @Test
    fun quickModels_prioritizeSelectedAndRecentsThenInstalledWithFallbacks() {
        val all = listOf(entry("sakura", false), entry("hy", true), entry("index", true), entry("qwen", true),
            entry("imported-a", true), entry("imported-b", true))
        data class Case(val selected: String?, val recent: List<String>, val limit: Int = 3, val expected: List<String>)
        listOf(
            Case("qwen", emptyList(), expected = listOf("qwen", "hy", "index")),
            Case("imported-b", listOf("imported-b", "imported-a", "qwen"), expected = listOf("imported-b", "imported-a", "qwen")),
            Case("qwen", listOf("missing", "index", "index", "hy"), expected = listOf("qwen", "index", "hy")),
            Case("missing", emptyList(), expected = listOf("hy", "index", "qwen")),
            Case(null, emptyList(), limit = 1, expected = listOf("hy")),
            Case("qwen", emptyList(), limit = 0, expected = emptyList()),
            Case("qwen", emptyList(), limit = -1, expected = emptyList()),
            Case(null, emptyList(), limit = 10, expected = listOf("hy", "index", "qwen", "imported-a", "imported-b", "sakura")),
        ).forEach { case ->
            assertEquals(case.expected,
                LocalModelQuickAccessPolicy.visible(all, case.selected, case.recent, case.limit).map { it.id })
        }
        val unavailable = all.map { it.copy(file = null) }
        assertEquals(listOf("sakura", "hy", "index"),
            LocalModelQuickAccessPolicy.visible(unavailable, null, emptyList()).map { it.id })
        assertEquals(emptyList<LocalModelEntry>(), LocalModelQuickAccessPolicy.visible(emptyList(), "qwen", listOf("hy")))
        assertEquals(listOf("qwen", "hy", "index"),
            LocalModelQuickAccessPolicy.visible(all + all, "qwen", listOf("qwen", "hy")).map { it.id })
    }

    private fun entry(id: String, installed: Boolean): LocalModelEntry = LocalModelEntry(
        id = id, name = id, kind = null, file = if (installed) File("/$id.gguf") else null,
        sizeBytes = 10, architecture = "qwen3", quantization = "Q8_0", chatTemplate = "template",
        config = LocalModelConfig(),
    )
}
