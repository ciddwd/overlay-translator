package com.gameocr.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SettingsCredentialSafetyTest {
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun closeStores() = scopes.forEach { it.cancel() }

    @Test fun failedCredentialChangesLeaveTheEntireStoreUnmodified() = runBlocking {
        val f = Fixture()
        f.repo.update { it.copy(apiKey = KEY, baseUrl = URL) }
        val before = f.store.data.first()
        f.cipher.failEncrypt = true
        expectRejected { f.repo.update { it.copy(apiKey = "new-secret", apiTimeoutSeconds = 43) } }
        assertEquals(before, f.store.data.first())
        f.cipher.failEncrypt = false
        assertEquals(KEY, f.repo.get().apiKey)
    }

    @Test fun unrelatedSavesDoNotReencryptExistingSecrets() = runBlocking {
        val f = Fixture()
        f.repo.update { it.copy(apiKey = KEY, baseUrl = URL) }
        val raw = f.raw("api_key")
        val calls = f.cipher.encryptCalls
        f.cipher.failEncrypt = true
        f.repo.update { it.copy(apiTimeoutSeconds = 43) }
        assertEquals(calls, f.cipher.encryptCalls)
        assertEquals(raw, f.raw("api_key"))
        assertEquals(43, f.repo.get().apiTimeoutSeconds)
    }

    @Test fun failedLegacyMigrationCanBeRetriedWithoutLosingPlaintext() = runBlocking {
        val f = Fixture()
        f.store.edit { it[stringPreferencesKey("api_key")] = KEY }
        val before = f.store.data.first()
        f.cipher.failEncrypt = true
        expectRejected { f.repo.migratePlaintextSecretsIfNeeded() }
        assertEquals(before, f.store.data.first())
        f.cipher.failEncrypt = false
        assertTrue(f.repo.migratePlaintextSecretsIfNeeded() > 0)
        assertEquals(KEY, f.repo.get().apiKey)
        assertTrue(f.raw("api_key")!!.startsWith(SettingsSecretCodec.PREFIX))
    }

    @Test fun unreadableSecretsBlockWritesAndDoNotEmitBlankSettings() = runBlocking {
        val f = Fixture()
        f.repo.update { it.copy(apiKey = KEY, baseUrl = URL) }
        val before = f.store.data.first()
        f.cipher.failDecrypt = true
        val read = async { f.repo.settings.first() }
        expectRejected { f.repo.update { it.copy(apiTimeoutSeconds = 43, apiKey = "") } }
        delay(40)
        assertFalse(read.isCompleted)
        assertEquals(before, f.store.data.first())
        f.cipher.failDecrypt = false
        assertEquals(KEY, withTimeout(3_000) { read.await() }.apiKey)
    }

    @Test fun explicitKeyClearingStillWorks() = runBlocking {
        val f = Fixture()
        f.repo.update { it.copy(apiKey = KEY) }
        f.repo.update { it.copy(apiKey = "") }
        assertEquals("", f.repo.get().apiKey)
    }

    @Test fun backgroundWritesReportFailureWithoutThrowingOrChangingTheStore() = runBlocking {
        val f = Fixture()
        assertTrue(f.repo.tryUpdate { it.copy(apiKey = KEY) })
        val before = f.store.data.first()
        f.cipher.failDecrypt = true
        assertFalse(f.repo.tryUpdate { it.copy(floatingButtonX = 123) })
        assertEquals(before, f.store.data.first())
    }

    @Test fun legacyPresetMigrationOnlyAssociatesTheMatchingCurrentConnection() = runBlocking {
        data class Case(val active: String, val otherUrl: String?, val expected: String)
        for (case in listOf(
            Case("legacy", null, KEY), Case("", null, KEY),
            Case("legacy", "https://different.test/v1", ""),
            Case("", URL, ""),
        )) {
            val f = Fixture()
            val source = Settings(baseUrl = URL, model = "old-model", apiKey = KEY)
            val p = preset("legacy", source.copy(baseUrl = case.otherUrl ?: URL))
            val presets = if (case.active.isEmpty() && case.otherUrl != null) listOf(p, p.copy(id = "other", name = "Other")) else listOf(p)
            f.seedLegacy(source.copy(translationPresets = presets, activeTranslationPresetId = case.active))
            f.repo.migratePlaintextSecretsIfNeeded()
            assertEquals(KEY, f.repo.get().apiKey)
            assertEquals(case.toString(), case.expected, f.repo.applyTranslationPreset(p.id)!!.apiKey)
        }
    }

    @Test fun legacyMigrationNeverAssociatesAMismatchingModelOrProvider() = runBlocking {
        for (source in listOf(
            Settings(baseUrl = URL, model = "different-model", apiKey = KEY),
            Settings(baseUrl = URL, model = "old-model", apiKey = KEY, openAiProvider = OpenAiProvider.BILIBILI),
        )) {
            val f = Fixture()
            val p = preset("legacy", Settings(baseUrl = URL, model = "old-model"))
            f.seedLegacy(source.copy(translationPresets = listOf(p), activeTranslationPresetId = p.id))
            f.repo.migratePlaintextSecretsIfNeeded()
            assertEquals("", f.repo.applyTranslationPreset(p.id)!!.apiKey)
        }
    }

    @Test fun legacyEmptyVaultRecordCanBeRecoveredFromTheMatchingActiveConnection() = runBlocking {
        val f = Fixture()
        val source = Settings(baseUrl = URL, model = "old-model", apiKey = KEY)
        val p = preset("legacy", source)
        f.seedLegacy(source.copy(translationPresets = listOf(p), activeTranslationPresetId = p.id))
        f.store.edit { it[stringPreferencesKey("local_preset_credentials_v1_legacy")] = "" }
        f.repo.migratePlaintextSecretsIfNeeded()
        assertEquals(KEY, f.repo.applyTranslationPreset(p.id)!!.apiKey)
    }

    @Test fun sameDeviceBundleRoundTripKeepsGlobalAndPresetKeys() = runBlocking {
        val f = Fixture()
        val source = Settings(baseUrl = URL, apiKey = KEY)
        val p = preset("cloud", source)
        f.repo.saveTranslationPreset(p, source)
        val before = f.repo.get()
        val out = ByteArrayOutputStream()
        SettingsBundleTransfer.write(out, before) { null }
        val preview = SettingsBundleTransfer.readPreview(out.toByteArray().inputStream())
        val merged = SettingsBundleTransfer.mergeImportedSettings(before, requireNotNull(preview.settings), emptyList())
        f.repo.updateAfterPresetImport(merged.presetResult.overwrittenNames.toSet()) { merged.settings }
        assertEquals(KEY, f.repo.get().apiKey)
        assertEquals(KEY, f.repo.applyTranslationPreset(p.id)!!.apiKey)
    }

    @Test fun importedChangedEndpointAndNewPresetsCannotBorrowKeys() = runBlocking {
        for (sameName in listOf(true, false)) {
            val f = Fixture()
            val source = Settings(baseUrl = URL, apiKey = KEY)
            val p = preset("cloud", source)
            f.repo.saveTranslationPreset(p, source)
            val imported = if (sameName) p.copy(baseUrl = "https://other.test/v1") else p.copy(id = "new", name = "New")
            f.repo.updateAfterPresetImport(setOf(imported.name)) { current ->
                current.copy(translationPresets = TranslationPresetTransfer.mergeImportedPresets(current.translationPresets, listOf(imported)).presets)
            }
            assertEquals("", f.repo.applyTranslationPreset(imported.id)!!.apiKey)
            if (!sameName) assertEquals(KEY, f.repo.applyTranslationPreset(p.id)!!.apiKey)
        }
    }

    @Test fun bundleRoundTripPreservesAnthropicAndCustomDeepLBindings() = runBlocking {
        for (engine in listOf(TranslatorEngine.ANTHROPIC, TranslatorEngine.DEEPL)) {
            val f = Fixture()
            val source = Settings(translatorEngine = engine, anthropicBaseUrl = "https://private-anthropic.test",
                anthropicApiKey = KEY, deeplBaseUrl = "https://private-deepl.test", deeplCustomToken = KEY)
            val p = preset("cloud", source)
            f.repo.saveTranslationPreset(p, source)
            val before = f.repo.get()
            val output = ByteArrayOutputStream()
            SettingsBundleTransfer.write(output, before) { null }
            val preview = SettingsBundleTransfer.readPreview(output.toByteArray().inputStream())
            val portable = requireNotNull(preview.settings)
            assertFalse(Json.encodeToString(portable).contains("private-anthropic.test"))
            assertFalse(Json.encodeToString(portable).contains("private-deepl.test"))
            val merged = SettingsBundleTransfer.mergeImportedSettings(before, portable, emptyList())
            f.repo.updateAfterPresetImport(merged.presetResult.overwrittenNames.toSet()) { merged.settings }
            val applied = requireNotNull(f.repo.applyTranslationPreset(p.id))
            if (engine == TranslatorEngine.ANTHROPIC) {
                assertEquals(source.anthropicBaseUrl, applied.anthropicBaseUrl)
                assertEquals(KEY, applied.anthropicApiKey)
            } else {
                assertEquals(source.deeplBaseUrl, applied.deeplBaseUrl)
                assertEquals(KEY, applied.deeplCustomToken)
            }
        }
    }

    @Test fun failedImportRestoresRawSettingsAndEveryPresetCredentialEvenIfCipherFails() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val f = Fixture()
            val source = Settings(baseUrl = URL, apiKey = KEY)
            val p = preset("cloud", source)
            f.repo.saveTranslationPreset(p, source)
            val before = f.repo.snapshotForImport()
            val raw = f.store.data.first()
            val changed = p.copy(baseUrl = "https://changed.test/v1")
            val failure = if (cancelled) kotlinx.coroutines.CancellationException("cancel import") else IllegalStateException("font failure")
            try {
                SettingsBundleImportTransaction<Unit, Unit, Unit, Settings>(
                    commitFont = { Unit }, prepareSettings = { Unit }, importGlossary = { 0 },
                    commitSettings = {
                        f.repo.updateAfterPresetImport(setOf(p.name), rollbackSnapshot = before) {
                            it.copy(translationPresets = listOf(changed))
                        }
                    },
                    finishFont = { f.cipher.failEncrypt = true; f.cipher.failDecrypt = true; throw failure },
                    restoreSettings = { f.repo.restoreImportSnapshot(before) },
                    restoreGlossary = {}, rollbackFont = {},
                ).run(listOf(Unit))
                fail("import must fail")
            } catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(raw, f.store.data.first())
            f.cipher.failEncrypt = false
            f.cipher.failDecrypt = false
            assertEquals(KEY, f.repo.get().apiKey)
            assertEquals(KEY, f.repo.applyTranslationPreset(p.id)!!.apiKey)
        }
    }

    @Test fun importRollbackKeepsUnrelatedConcurrentSettingsAndGalleryCredentials() = runBlocking {
        val f = Fixture()
        val source = Settings(baseUrl = URL, apiKey = KEY)
        val p = preset("cloud", source)
        f.repo.saveTranslationPreset(p, source)
        val before = f.repo.snapshotForImport()
        f.repo.updateAfterPresetImport(setOf(p.name), rollbackSnapshot = before) {
            it.copy(translationPresets = listOf(p.copy(baseUrl = "https://other.test/v1")))
        }
        f.repo.update { it.copy(apiTimeoutSeconds = 77) }
        val gallery = preset("gallery_new", source.copy(apiKey = "gallery-key"))
        f.repo.rememberSnapshotCredentials(gallery, source.copy(apiKey = "gallery-key"))
        f.repo.restoreImportSnapshot(before)
        assertEquals(77, f.repo.get().apiTimeoutSeconds)
        assertEquals(KEY, f.repo.applyTranslationPreset(p.id)!!.apiKey)
        assertEquals("gallery-key", f.repo.settingsForSnapshot(gallery).apiKey)
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        try { block(); fail("write must be rejected") } catch (_: SettingsSecretStorageException) { }
    }

    private fun preset(id: String, settings: Settings) = TranslationPresetCatalog.fromSettings(id, id, id, settings)
    private inner class Fixture {
        private val root = Files.createTempDirectory("settings-credential-safety-").toFile()
        val cipher = TestCipher()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)) {
            File(root, "datastore/game_ocr_settings.preferences_pb")
        }
        val repo = SettingsRepository(object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getPackageName(): String = "com.gameocr.app.test"
        }, cipher, store).apply { setDefaultPromptProvidersForTest({ "prompt" }, { "dictionary" }) }
        suspend fun raw(name: String) = store.data.first()[stringPreferencesKey(name)]
        suspend fun seedLegacy(source: Settings) {
            store.edit {
                it[stringPreferencesKey("api_key")] = source.apiKey
                it[stringPreferencesKey("base_url")] = source.baseUrl
                it[stringPreferencesKey("model")] = source.model
                it[stringPreferencesKey("openai_provider")] = source.openAiProvider.name
                it[stringPreferencesKey("active_translation_preset_id")] = source.activeTranslationPresetId
                it[stringPreferencesKey("translation_presets_json")] = Json.encodeToString(source.translationPresets)
            }
        }
    }
    private class TestCipher : SettingsSecretCipher {
        var failEncrypt = false
        var failDecrypt = false
        var encryptCalls = 0
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plainText: String): String {
            check(!failEncrypt)
            encryptCalls++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plainText.toByteArray()))
        }
        override fun decrypt(cipherText: String): String {
            check(!failDecrypt)
            val bytes = Base64.getDecoder().decode(cipherText)
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    private companion object { const val KEY = "synthetic-key"; const val URL = "https://service.test/v1/" }
}
