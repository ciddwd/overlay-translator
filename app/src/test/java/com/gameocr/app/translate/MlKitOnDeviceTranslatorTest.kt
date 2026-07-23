package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitOnDeviceTranslatorTest {
    @Test
    fun translate_tableDrivenExplicitSourceCases() {
        data class Case(
            val name: String,
            val source: String,
            val sourceLang: String,
            val targetLang: String,
            val expected: String?,
            val expectedClients: Int,
        )

        val cases = listOf(
            Case("empty", "  ", "en", "zh-CN", expected = null, expectedClients = 0),
            Case("same language passthrough", " hello ", "en-US", "en", expected = "hello", expectedClients = 0),
            Case("Latin shortcut English", " hello ", "en", "zh-TW", expected = "en>zh:hello", expectedClients = 1),
            Case("Japanese", "猫", "ja", "zh-CN", expected = "ja>zh:猫", expectedClients = 1),
            Case("Korean", "고양이", "ko-KR", "en", expected = "ko>en:고양이", expectedClients = 1),
            Case("Chinese", "你好", "zh-CN", "ja", expected = "zh>ja:你好", expectedClients = 1),
        )

        cases.forEach { case ->
            val factory = FakeClientFactory()
            val translator = translator(factory)
            val result = runBlocking {
                translator.translate(
                    case.source,
                    Settings(sourceLang = case.sourceLang, targetLang = case.targetLang),
                )
            }

            assertEquals(case.name, case.expected, result)
            assertEquals("${case.name} clients", case.expectedClients, factory.clients.size)
            assertTrue("${case.name} clients closed", factory.clients.all(FakeClient::closed))
            assertTrue("${case.name} downloads on demand", factory.clients.all { it.downloadCalls == 1 })
        }
    }

    @Test
    fun translateFailures_tableDrivenAndAlwaysCloseClient() {
        data class Case(
            val name: String,
            val sourceLang: String = "en",
            val targetLang: String = "zh-CN",
            val createFailure: Boolean = false,
            val downloadFailure: Boolean = false,
            val translationFailure: Boolean = false,
            val blankTranslation: Boolean = false,
            val expectedMessage: String?,
        )

        val cases = listOf(
            Case("auto source is silent", sourceLang = "auto", expectedMessage = null),
            Case("blank source is silent", sourceLang = " ", expectedMessage = null),
            Case("unsupported source", sourceLang = "yue", expectedMessage = "不支持源语言"),
            Case("auto target", targetLang = "auto", expectedMessage = "不能使用自动检测"),
            Case("client creation", createFailure = true, expectedMessage = "客户端创建失败"),
            Case("model download", downloadFailure = true, expectedMessage = "语言模型下载失败"),
            Case("translation", translationFailure = true, expectedMessage = "端侧翻译失败"),
            Case("blank result", blankTranslation = true, expectedMessage = "空译文"),
        )

        cases.forEach { case ->
            val factory = FakeClientFactory(
                createFailure = case.createFailure,
                downloadFailure = case.downloadFailure,
                translationFailure = case.translationFailure,
                blankTranslation = case.blankTranslation,
            )
            val translator = translator(factory)
            val error = runCatching {
                runBlocking {
                    translator.translate(
                        "hello",
                        Settings(sourceLang = case.sourceLang, targetLang = case.targetLang),
                    )
                }
            }.exceptionOrNull()

            assertTrue("${case.name}: $error", error is TranslationException)
            if (case.expectedMessage == null) {
                assertTrue("${case.name}: ${error?.message}", error?.message.isNullOrEmpty())
            } else {
                assertTrue(
                    "${case.name}: ${error?.message}",
                    error?.message.orEmpty().contains(case.expectedMessage),
                )
            }
            assertTrue("${case.name} clients closed", factory.clients.all(FakeClient::closed))
        }
    }

    @Test
    fun manualModelDownload_tableDrivenDownloadsWithoutTranslating() = runBlocking {
        data class Case(
            val name: String,
            val sourceLang: String,
            val targetLang: String,
            val expectedClients: Int,
        )

        val cases = listOf(
            Case("Japanese to Chinese", "ja", "zh-CN", 1),
            Case("Korean to English", "ko-KR", "en", 1),
            Case("same language needs no model", "en-US", "en", 0),
        )

        cases.forEach { case ->
            val factory = FakeClientFactory()
            translator(factory).ensureLanguagePairModelsDownloaded(
                sourceTag = case.sourceLang,
                targetTag = case.targetLang,
            )

            assertEquals(case.name, case.expectedClients, factory.clients.size)
            assertTrue("${case.name} downloaded", factory.clients.all { it.downloadCalls == 1 })
            assertTrue("${case.name} did not translate", factory.clients.all { it.translateCalls == 0 })
            assertTrue("${case.name} clients closed", factory.clients.all(FakeClient::closed))
        }
    }

    @Test
    fun modelAvailability_tableDrivenUsesReusablePerLanguageModels() = runBlocking {
        data class Case(
            val name: String,
            val sourceLang: String,
            val targetLang: String,
            val downloadedLanguages: Set<String>,
            val expectedMissing: Set<String>,
        )

        val cases = listOf(
            Case("English is built in", "en", "en-US", emptySet(), emptySet()),
            Case("same non-English language is passthrough", "ko", "ko-KR", emptySet(), emptySet()),
            Case("English to Korean", "en", "ko", setOf("ko"), emptySet()),
            Case("Korean to English reuses Korean", "ko-KR", "en", setOf("ko"), emptySet()),
            Case("Korean missing", "en", "ko", emptySet(), setOf("ko")),
            Case("Japanese to Korean both present", "ja", "ko", setOf("ja", "ko"), emptySet()),
            Case("Japanese to Korean missing Japanese", "ja", "ko", setOf("ko"), setOf("ja")),
            Case("Japanese to Korean both missing", "ja", "ko", emptySet(), setOf("ja", "ko")),
            Case("Chinese regional tag", "zh-TW", "en", setOf("zh"), emptySet()),
        )

        cases.forEach { case ->
            val translator = translator(
                factory = FakeClientFactory(),
                downloadedLanguages = case.downloadedLanguages,
            )

            assertEquals(
                case.name,
                case.expectedMissing,
                translator.getMissingLanguageModels(case.sourceLang, case.targetLang),
            )
            assertEquals(
                "${case.name} readiness",
                case.expectedMissing.isEmpty(),
                translator.areLanguagePairModelsDownloaded(case.sourceLang, case.targetLang),
            )
        }
    }

    @Test
    fun manualModelDownload_tableDrivenFailuresRemainActionable() {
        data class Case(
            val name: String,
            val factory: FakeClientFactory,
            val expectedMessage: String,
        )

        val cases = listOf(
            Case(
                "client creation",
                FakeClientFactory(createFailure = true),
                "客户端创建失败",
            ),
            Case(
                "model download",
                FakeClientFactory(downloadFailure = true),
                "语言模型下载失败",
            ),
        )

        cases.forEach { case ->
            val error = runCatching {
                runBlocking {
                    translator(case.factory).ensureLanguagePairModelsDownloaded("ko", "zh-CN")
                }
            }.exceptionOrNull()

            assertTrue("${case.name}: $error", error is TranslationException)
            assertTrue(
                "${case.name}: ${error?.message}",
                error?.message.orEmpty().contains(case.expectedMessage),
            )
            assertTrue("${case.name} clients closed", case.factory.clients.all(FakeClient::closed))
        }
    }

    @Test
    fun batch_tableDrivenItemsAreIsolatedAndIncremental() = runBlocking {
        val factory = FakeClientFactory(failingTexts = setOf("fail"))
        val translator = translator(factory)
        val updates = mutableListOf<BatchTranslationUpdate>()
        val sources = listOf("one", " ", "two", "fail")

        val results = translator.translateBatchIncremental(
            sources = sources,
            settings = Settings(sourceLang = "en", targetLang = "zh-CN"),
            onUpdate = updates::add,
        )

        assertEquals(listOf("en>zh:one", null, "en>zh:two", null), results)
        assertEquals(sources.indices.toList(), updates.map(BatchTranslationUpdate::index))
        assertEquals(results, updates.map(BatchTranslationUpdate::text))
        assertTrue(updates.all { (it.elapsedMs ?: -1L) >= 0L })
        assertEquals(3, factory.clients.size)
        assertTrue(factory.clients.all(FakeClient::closed))
    }

    @Test
    fun cache_reusesSuccessfulTranslationWithoutCreatingAnotherClient() = runBlocking {
        val factory = FakeClientFactory()
        val translator = translator(factory)
        val settings = Settings(sourceLang = "ko", targetLang = "zh-CN")

        assertEquals("ko>zh:안녕", translator.translate("안녕", settings))
        assertEquals("ko>zh:안녕", translator.translate("안녕", settings))
        assertEquals(1, factory.clients.size)
        assertEquals(1, factory.clients.single().downloadCalls)
        assertTrue(factory.clients.single().closed)
    }

    @Test
    fun testConnection_tableDrivenRequiresExplicitSource() = runBlocking {
        data class Case(val name: String, val sourceLang: String, val success: Boolean, val messagePart: String)

        val cases = listOf(
            Case("Japanese", "ja", true, "こんにちは"),
            Case("Korean", "ko", true, "안녕하세요"),
            Case("automatic", "auto", false, ""),
        )

        cases.forEach { case ->
            val result = translator(FakeClientFactory()).testConnection(
                Settings(sourceLang = case.sourceLang, targetLang = "zh-CN"),
            )
            assertEquals(case.name, case.success, result.success)
            if (case.success) {
                assertTrue("${case.name}: ${result.message}", result.message.contains(case.messagePart))
            } else {
                assertEquals(case.name, "", result.message)
            }
        }
    }

    private fun translator(
        factory: FakeClientFactory,
        downloadedLanguages: Set<String> = emptySet(),
    ): MlKitOnDeviceTranslator =
        MlKitOnDeviceTranslator(
            clientFactory = factory,
            downloadedLanguageProvider = MlKitDownloadedLanguageProvider { downloadedLanguages },
            cache = TranslationCache(capacity = 16),
        )

    private class FakeClientFactory(
        private val createFailure: Boolean = false,
        private val downloadFailure: Boolean = false,
        private val translationFailure: Boolean = false,
        private val blankTranslation: Boolean = false,
        private val failingTexts: Set<String> = emptySet(),
    ) : MlKitTranslationClientFactory {
        val clients = mutableListOf<FakeClient>()

        override fun create(sourceLanguage: String, targetLanguage: String): MlKitTranslationClient {
            if (createFailure) throw IOException("cannot create")
            return FakeClient(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                downloadFailure = downloadFailure,
                translationFailure = translationFailure,
                blankTranslation = blankTranslation,
                failingTexts = failingTexts,
            ).also(clients::add)
        }
    }

    private class FakeClient(
        private val sourceLanguage: String,
        private val targetLanguage: String,
        private val downloadFailure: Boolean,
        private val translationFailure: Boolean,
        private val blankTranslation: Boolean,
        private val failingTexts: Set<String>,
    ) : MlKitTranslationClient {
        var downloadCalls: Int = 0
        var translateCalls: Int = 0
        var closed: Boolean = false

        override suspend fun downloadModelIfNeeded() {
            downloadCalls += 1
            if (downloadFailure) throw IOException("download offline")
        }

        override suspend fun translate(text: String): String {
            translateCalls += 1
            if (translationFailure || text in failingTexts) throw IOException("translate failed")
            if (blankTranslation) return "  "
            return "$sourceLanguage>$targetLanguage:$text"
        }

        override fun close() {
            closed = true
        }
    }
}
