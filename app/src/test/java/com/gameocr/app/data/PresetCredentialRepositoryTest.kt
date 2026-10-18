package com.gameocr.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class PresetCredentialRepositoryTest {
    private val json = Json { encodeDefaults = true }
    private val storeScopes = mutableListOf<CoroutineScope>()

    @After
    fun closeStores() { storeScopes.forEach { it.cancel() } }

    @Test
    fun saveReopenSwitchAndOverwrite_tableDriven() = runBlocking {
        val fixture = Fixture()
        val repo = fixture.repo()
        val sources = listOf(
            Settings(baseUrl = "https://deepseek.test/v1", model = "deepseek", apiKey = "deepseek-secret"),
            Settings(baseUrl = "https://glm.test/v1", model = "glm", apiKey = "glm-secret"),
            Settings(baseUrl = "https://glm.test/v1", model = "glm", apiKey = "other-account-secret"),
            Settings(baseUrl = "http://localhost:8080/v1", model = "local", apiKey = ""),
            Settings(translatorEngine = TranslatorEngine.ANTHROPIC, anthropicApiKey = "anthropic-secret"),
        )
        val presets = sources.mapIndexed { index, settings -> preset("p$index", settings) }
        sources.zip(presets).forEach { (source, preset) -> repo.saveTranslationPreset(preset, source) }
        val reopened = fixture.repo()
        for (index in listOf(0, 1, 2, 0, 3, 4, 1)) {
            val result = requireNotNull(reopened.applyTranslationPreset(presets[index].id))
            val expected = sources[index]
            if (expected.translatorEngine == TranslatorEngine.ANTHROPIC) {
                assertEquals(expected.anthropicBaseUrl, result.anthropicBaseUrl)
                assertEquals(expected.anthropicModel, result.anthropicModel)
                assertEquals(expected.anthropicApiKey, result.anthropicApiKey)
            } else {
                assertEquals(expected.model, result.model)
                assertEquals(expected.baseUrl, result.baseUrl)
                assertEquals(expected.apiKey, result.apiKey)
            }
        }
        reopened.saveTranslationPreset(presets[0], sources[0].copy(apiKey = ""))
        assertEquals("", reopened.applyTranslationPreset(presets[0].id)?.apiKey)
        assertEquals("glm-secret", reopened.applyTranslationPreset(presets[1].id)?.apiKey)
        val raw = File(fixture.root, "datastore/game_ocr_settings.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        listOf("deepseek-secret", "glm-secret", "other-account-secret", "anthropic-secret").forEach { assertFalse(raw.contains(it)) }
    }

    @Test
    fun missingLegacyDeletedAndChangedEndpoint_useEmptyKey() = runBlocking {
        val repo = Fixture().repo()
        val source = Settings(apiKey = "secret", baseUrl = "https://original.test/v1")
        val p = preset("legacy", source)
        repo.update { source.copy(translationPresets = listOf(p)) }
        assertEquals("", repo.applyTranslationPreset(p.id)?.apiKey)
        repo.saveTranslationPreset(p, source)
        repo.update { it.copy(translationPresets = listOf(p.copy(baseUrl = "https://changed.test/v1"))) }
        assertEquals("", repo.applyTranslationPreset(p.id)?.apiKey)
        repo.update { it.copy(translationPresets = emptyList()) }
        repo.update { it.copy(translationPresets = listOf(p)) }
        assertEquals("", repo.applyTranslationPreset(p.id)?.apiKey)
        assertNull(repo.applyTranslationPreset("unknown"))
    }

    @Test
    fun sameConfigurationWithDifferentKeys_isRecognizedAsADifferentLocalPreset() = runBlocking {
        val repo = Fixture().repo()
        val a = Settings(apiKey = "key-a")
        val b = a.copy(apiKey = "key-b")
        repo.saveTranslationPreset(preset("a", a), a)
        repo.saveTranslationPreset(preset("b", b), b)
        assertTrue("a" in repo.matchingCredentialPresetIds(a))
        assertFalse("b" in repo.matchingCredentialPresetIds(a))
        assertTrue("b" in repo.matchingCredentialPresetIds(b))
        assertFalse("a" in repo.matchingCredentialPresetIds(b))
        assertTrue(repo.matchingCredentialPresetIds(a.copy(apiKey = "unsaved" )).isEmpty())
    }

    @Test
    fun encryptionFailure_clearsCredentialsButKeepsPresetAndOtherSettings() = runBlocking {
        val fixture = Fixture()
        val repo = fixture.repo()
        val source = PresetCredentialPolicyTest().withCredentials(Settings(model = "keep-model", promptTemplate = "keep-prompt"), "never-plaintext")
        val p = preset("failure", source)
        repo.saveTranslationPreset(p, source)
        fixture.cipher.fail = true
        repo.saveTranslationPreset(p, source.copy(apiKey = "replacement-secret"))
        val saved = repo.get()
        assertEquals("keep-model", saved.model)
        assertEquals("keep-prompt", saved.promptTemplate)
        assertEquals(p.id, saved.translationPresets.single().id)
        assertEquals("", saved.apiKey)
        repo.update { source.copy(translationPresets = listOf(p)) }
        val values = json.encodeToJsonElement(repo.get()).jsonObject
        PresetCredentialPolicy.credentialFields.forEach { assertEquals(it, "", values[it]?.jsonPrimitive?.content) }
        fixture.cipher.fail = false
        assertEquals("", repo.applyTranslationPreset(p.id)?.apiKey)
        val raw = File(fixture.root, "datastore/game_ocr_settings.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("replacement-secret"))
        assertFalse(raw.contains("never-plaintext"))
    }

    @Test
    fun duplicateAndImportCollision_doNotBorrowGlobalOrSameNameCredentials() = runBlocking {
        for (sameId in listOf(true, false)) {
            val repo = Fixture().repo()
            val source = Settings(apiKey = "original-secret")
            val p = preset("original", source)
            repo.saveTranslationPreset(p, source)
            repo.update { it.copy(apiKey = "global-other-secret") }
            val duplicate = requireNotNull(repo.duplicateTranslationPreset(p.id, "copy", "copy"))
            assertEquals("original-secret", repo.applyTranslationPreset(duplicate.id)?.apiKey)
            val imported = p.copy(id = if (sameId) p.id else "new-imported-id")
            repo.updateAfterPresetImport(setOf(imported.name)) { current ->
                current.copy(translationPresets = TranslationPresetTransfer.mergeImportedPresets(current.translationPresets, listOf(imported)).presets)
            }
            assertEquals("", repo.applyTranslationPreset(p.id)?.apiKey)
            assertEquals("original-secret", repo.applyTranslationPreset(duplicate.id)?.apiKey)
        }
    }

    @Test
    fun concurrentSwitchAndSaveThenApply_keepAddressModelAndKeyTogether() = runBlocking {
        val repo = Fixture().repo()
        val a = Settings(baseUrl = "https://a.test", model = "a", apiKey = "key-a")
        val b = Settings(baseUrl = "https://b.test", model = "b", apiKey = "key-b")
        repo.saveTranslationPreset(preset("a", a), a)
        repo.saveTranslationPreset(preset("b", b), b)
        (0 until 12).map { index -> async {
            val expected = if (index % 2 == 0) a else b
            val result = requireNotNull(repo.applyTranslationPreset(expected.model))
            assertEquals(expected.baseUrl, result.baseUrl)
            assertEquals(expected.apiKey, result.apiKey)
        } }.awaitAll()
        repo.update { a.copy(translationPresets = it.translationPresets, apiKey = "new-a") }
        assertEquals("key-b", repo.applyTranslationPreset("b", preset("saved-draft", a))?.apiKey)
        assertEquals("new-a", repo.applyTranslationPreset("saved-draft")?.apiKey)
    }

    @Test
    fun gallerySnapshotKeepsItsOwnCredentialsWithoutSwitchingActivePreset() = runBlocking {
        val repo = Fixture().repo()
        val original = Settings(apiKey = "gallery-secret", baseUrl = "https://gallery.test")
        val snapshot = preset("gallery_test", original)
        repo.rememberSnapshotCredentials(snapshot, original)
        repo.update { it.copy(apiKey = "other-secret", activeTranslationPresetId = "other", baseUrl = "https://other.test") }
        assertEquals("gallery-secret", repo.settingsForSnapshot(snapshot).apiKey)
        assertEquals("other", repo.get().activeTranslationPresetId)
        repo.forgetSnapshotCredentials(snapshot.id)
        assertEquals("", repo.settingsForSnapshot(snapshot).apiKey)
    }

    @Test
    fun exports_tableDriven_neverContainCredentialValuesOrLocalCiphertext() = runBlocking {
        val fixture = Fixture()
        val repo = fixture.repo()
        val source = PresetCredentialPolicyTest().withCredentials(Settings(), "export-forbidden")
        repo.update { source }
        repo.saveTranslationPreset(preset("export", source), source)
        val settings = repo.get()
        val output = ByteArrayOutputStream()
        SettingsBundleTransfer.write(output, settings) { null }
        val manifest = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            zip.nextEntry
            zip.readBytes().toString(Charsets.UTF_8)
        }
        val legacyRoundTrip = TranslationPresetTransfer.decodeEncrypted(TranslationPresetTransfer.encodeEncrypted(settings.translationPresets))
        val candidates = listOf(manifest, json.encodeToJsonElement(legacyRoundTrip).toString(), SettingsFieldPolicy.formatDiagnostics(settings))
        for (text in candidates) {
            assertFalse(text.contains("export-forbidden"))
            assertFalse(text.contains("local_preset_credentials"))
            assertFalse(text.contains("PresetCredentialRecord"))
            fixture.cipher.ciphertexts.forEach { assertFalse(text.contains(it)) }
        }
    }

    @Test
    fun cloudPresetsAndOfflinePreset_roundTripWithoutOverwritingSavedConnections() = runBlocking {
        val fixture = Fixture()
        val repo = fixture.repo()
        val a = Settings(baseUrl = "https://a.test/v1", model = "model-a", apiKey = "key-a")
        val b = Settings(baseUrl = "https://b.test/v1", model = "model-b", apiKey = "key-b")
        val c = a.copy(model = "same-endpoint-other-account", apiKey = "key-c")
        val empty = a.copy(model = "no-auth", apiKey = "")
        val sources = mapOf("a" to a, "b" to b, "c" to c, "empty" to empty)
        sources.forEach { (id, source) -> repo.saveTranslationPreset(preset(id, source), source) }
        val reopened = fixture.repo()
        for (id in listOf("a", "b", "c", "empty", "a", "b")) {
            val expected = sources.getValue(id)
            val cloud = requireNotNull(reopened.applyTranslationPreset(id))
            assertEquals(expected.baseUrl, cloud.baseUrl)
            assertEquals(expected.model, cloud.model)
            assertEquals(expected.apiKey, cloud.apiKey)
            val offline = requireNotNull(reopened.applyTranslationPreset(TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH))
            assertEquals(expected.baseUrl, offline.baseUrl)
            assertEquals(expected.model, offline.model)
            assertEquals(expected.apiKey, offline.apiKey)
            assertEquals(TranslatorEngine.LOCAL_SAKURA, offline.translatorEngine)
            assertEquals(OcrEngineKind.MANGA_OCR_JA, offline.ocrEngine)
            assertTrue(TranslationPresetCatalog.matchesSettings(TranslationPresetCatalog.builtIns().single(), offline))
            assertTrue(TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH in reopened.matchingCredentialPresetIds(offline))
        }
        // A manual edit must not update a saved preset merely because another preset is applied.
        reopened.applyTranslationPreset("a")
        reopened.update { it.copy(baseUrl = "https://unsaved.test", model = "unsaved", apiKey = "unsaved") }
        reopened.applyTranslationPreset("b")
        assertEquals("key-a", reopened.applyTranslationPreset("a")?.apiKey)
        assertEquals("https://a.test/v1", reopened.get().baseUrl)
        // Explicitly saving A still cannot modify B or C, even when A and C share an endpoint.
        reopened.saveTranslationPreset(preset("a", a.copy(model = "updated-a")), a.copy(apiKey = "updated-key-a"))
        assertEquals("key-b", reopened.applyTranslationPreset("b")?.apiKey)
        assertEquals("key-c", reopened.applyTranslationPreset("c")?.apiKey)
        assertEquals("updated-key-a", reopened.applyTranslationPreset("a")?.apiKey)
    }

    @Test
    fun inactiveCloudGroupsAndLocalCustomPresets_preserveExistingConnections() = runBlocking {
        val repo = Fixture().repo()
        val cloud = Settings(baseUrl = "https://cloud.test/v1", model = "cloud-model", apiKey = "cloud-key",
            anthropicBaseUrl = "https://anthropic.test", anthropicModel = "anthropic-model", anthropicApiKey = "anthropic-key",
            umiOcrBaseUrl = "http://ocr.test", tencentRegion = "test-region")
        repo.update { cloud }
        val offline = Settings(translatorEngine = TranslatorEngine.LOCAL_SAKURA, ocrEngine = OcrEngineKind.MANGA_OCR_JA)
        repo.saveTranslationPreset(preset("local", offline), offline)
        val local = repo.get()
        assertEquals(cloud.baseUrl, local.baseUrl)
        assertEquals(cloud.apiKey, local.apiKey)
        assertEquals(cloud.anthropicBaseUrl, local.anthropicBaseUrl)
        assertEquals(cloud.anthropicApiKey, local.anthropicApiKey)
        assertEquals(cloud.umiOcrBaseUrl, local.umiOcrBaseUrl)
        assertEquals(cloud.tencentRegion, local.tencentRegion)
        val anthropic = cloud.copy(translatorEngine = TranslatorEngine.ANTHROPIC,
            baseUrl = "https://unused.test", model = "unused", apiKey = "unused")
        repo.saveTranslationPreset(preset("anthropic", anthropic), anthropic)
        assertEquals(cloud.baseUrl, repo.get().baseUrl)
        assertEquals(cloud.apiKey, repo.get().apiKey)
        assertEquals(cloud.anthropicBaseUrl, repo.get().anthropicBaseUrl)
        assertEquals(cloud.anthropicApiKey, repo.get().anthropicApiKey)
    }

    private fun preset(id: String, source: Settings) = TranslationPresetCatalog.fromSettings(id, id, id, source)

    private inner class Fixture {
        val root: File = Files.createTempDirectory("preset-credential-test").toFile()
        val cipher = TestCipher()
        private val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(storeScopes::add),
        ) { File(root, "datastore/game_ocr_settings.preferences_pb") }
        fun repo() = SettingsRepository(object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getPackageName(): String = "com.gameocr.app.presettest"
        }, cipher, store).apply { setDefaultPromptProvidersForTest({ "default prompt" }, { "default dictionary" }) }
    }

    private class TestCipher : SettingsSecretCipher {
        var fail = false
        val ciphertexts = mutableListOf<String>()
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plainText: String): String {
            check(!fail)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plainText.toByteArray())).also(ciphertexts::add)
        }
        override fun decrypt(cipherText: String): String {
            val bytes = Base64.getDecoder().decode(cipherText)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
}
