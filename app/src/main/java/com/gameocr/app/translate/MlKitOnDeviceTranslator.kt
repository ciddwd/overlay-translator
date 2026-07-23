package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import timber.log.Timber

interface MlKitTranslationClient : Closeable {
    suspend fun downloadModelIfNeeded()
    suspend fun translate(text: String): String
}

fun interface MlKitTranslationClientFactory {
    fun create(sourceLanguage: String, targetLanguage: String): MlKitTranslationClient
}

fun interface MlKitDownloadedLanguageProvider {
    suspend fun getDownloadedLanguages(): Set<String>
}

class GoogleMlKitDownloadedLanguageProvider @Inject constructor() :
    MlKitDownloadedLanguageProvider {
    override suspend fun getDownloadedLanguages(): Set<String> =
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
            .mapTo(mutableSetOf()) { it.language }
}

class GoogleMlKitTranslationClientFactory @Inject constructor() :
    MlKitTranslationClientFactory {
    override fun create(sourceLanguage: String, targetLanguage: String): MlKitTranslationClient {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)
        return object : MlKitTranslationClient {
            override suspend fun downloadModelIfNeeded() {
                // Allow the user's active network. The settings UI warns that the first use
                // downloads an approximately 30 MB language model.
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()
            }

            override suspend fun translate(text: String): String = translator.translate(text).await()

            override fun close() = translator.close()
        }
    }
}

/**
 * Google ML Kit translation running on the device after its language model is downloaded.
 *
 * The Translation API requires an explicit source language. This engine intentionally rejects
 * `sourceLang=auto`; language selection belongs to the user, not to a separate identification
 * model. A client is closed after every uncached request, as required by the ML Kit API contract.
 * Batch requests run sequentially to avoid simultaneous downloads and excessive native clients
 * on a frame containing many OCR blocks.
 */
@Singleton
class MlKitOnDeviceTranslator @Inject constructor(
    private val clientFactory: MlKitTranslationClientFactory,
    private val downloadedLanguageProvider: MlKitDownloadedLanguageProvider,
    private val cache: TranslationCache,
) : Translator {
    override val prefersBatch: Boolean = true

    override suspend fun translate(source: String, settings: Settings): String? {
        val text = source.trim()
        if (text.isEmpty()) return null

        // Resolve both sides before reading the cache so an old cached AUTO result can never make
        // this explicit-source engine appear to support automatic detection.
        val sourceLanguage = MlKitLanguagePolicy.resolveConfiguredSource(settings.sourceLang)
        val targetLanguage = MlKitLanguagePolicy.resolveTarget(settings.targetLang)
        val cacheKey = cache.key(
            source = text,
            model = "google-mlkit",
            targetLang = targetLanguage,
            prompt = sourceLanguage,
        )
        cache.get(cacheKey, settings)?.let { return it }

        if (sourceLanguage == targetLanguage) {
            cache.put(cacheKey, text, settings)
            return text
        }

        val translated = translateWithClient(text, sourceLanguage, targetLanguage)
        if (translated.isBlank()) {
            throw TranslationException("ML Kit 返回了空译文")
        }
        cache.put(cacheKey, translated, settings)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        translate(source, settings)?.let { emit(it) }
    }

    override suspend fun translateBatch(
        sources: List<String>,
        settings: Settings,
    ): List<String?> = translateBatchIncremental(sources, settings) { }

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        val results = MutableList<String?>(sources.size) { null }
        sources.forEachIndexed { index, source ->
            val startedAtNs = System.nanoTime()
            val result = runCatching { translate(source, settings) }
                .onFailure {
                    Timber.tag("MlKitTrans").w(it, "batch item failed: index=%d", index)
                }
                .getOrNull()
            val elapsedMs =
                ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
            results[index] = result
            onUpdate(
                BatchTranslationUpdate(
                    index = index,
                    text = result,
                    elapsedMs = elapsedMs,
                )
            )
        }
        return results
    }

    override suspend fun testConnection(settings: Settings): TestResult = runCatching {
        val source = MlKitLanguagePolicy.resolveConfiguredSource(settings.sourceLang)
        val target = MlKitLanguagePolicy.resolveTarget(settings.targetLang)
        val probeText = when (source) {
            TranslateLanguage.JAPANESE -> "こんにちは"
            TranslateLanguage.KOREAN -> "안녕하세요"
            TranslateLanguage.CHINESE -> "你好"
            TranslateLanguage.SPANISH -> "hola"
            else -> "hello"
        }
        val translated = translate(
            source = probeText,
            settings = settings.copy(sourceLang = source, targetLang = target),
        ).orEmpty()
        TestResult(true, "OK · $probeText → $translated")
    }.getOrElse { error ->
        TestResult(false, error.message ?: error.javaClass.simpleName)
    }

    /** Explicit user action from Settings; downloads models without translating any text. */
    suspend fun ensureLanguagePairModelsDownloaded(sourceTag: String, targetTag: String) {
        val sourceLanguage = MlKitLanguagePolicy.resolveConfiguredSource(sourceTag)
        val targetLanguage = MlKitLanguagePolicy.resolveTarget(targetTag)
        if (sourceLanguage == targetLanguage) return

        createTranslationClient(sourceLanguage, targetLanguage).use { client ->
            downloadModels(client)
        }
    }

    /** Returns true when every language-specific model required by this direction is on-device. */
    suspend fun areLanguagePairModelsDownloaded(
        sourceTag: String,
        targetTag: String,
    ): Boolean = getMissingLanguageModels(sourceTag, targetTag).isEmpty()

    suspend fun getDownloadedLanguageModels(): Set<String> =
        downloadedLanguageProvider.getDownloadedLanguages()

    /** Returns canonical ML Kit language tags for models still missing from this device. */
    suspend fun getMissingLanguageModels(sourceTag: String, targetTag: String): Set<String> {
        val sourceLanguage = MlKitLanguagePolicy.resolveConfiguredSource(sourceTag)
        val targetLanguage = MlKitLanguagePolicy.resolveTarget(targetTag)
        val requiredLanguages = MlKitLanguagePolicy.requiredDownloadLanguages(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
        )
        if (requiredLanguages.isEmpty()) return emptySet()
        return requiredLanguages - downloadedLanguageProvider.getDownloadedLanguages()
    }

    private suspend fun translateWithClient(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val client = createTranslationClient(sourceLanguage, targetLanguage)
        return client.use {
            downloadModels(it)
            try {
                it.translate(text)
            } catch (error: Exception) {
                throw TranslationException("ML Kit 端侧翻译失败: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }
    }

    private fun createTranslationClient(
        sourceLanguage: String,
        targetLanguage: String,
    ): MlKitTranslationClient = try {
        clientFactory.create(sourceLanguage, targetLanguage)
    } catch (error: Exception) {
        throw TranslationException(
            "ML Kit 翻译客户端创建失败: ${error.message ?: error.javaClass.simpleName}",
            error,
        )
    }

    private suspend fun downloadModels(client: MlKitTranslationClient) {
        try {
            client.downloadModelIfNeeded()
        } catch (error: Exception) {
            throw TranslationException(
                "ML Kit 语言模型下载失败: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }
}

internal object MlKitLanguagePolicy {
    val supportedLanguageTags: Set<String> = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en",
        "eo", "es", "et", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr",
        "ht", "hu", "id", "is", "it", "ja", "ka", "kn", "ko", "lt", "lv", "mk",
        "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq",
        "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    fun isSupportedLanguageTag(languageTag: String): Boolean {
        val normalized = normalize(languageTag)
        if (normalized.isEmpty() || normalized == "auto") return false
        val canonicalTag = canonicalize(normalized)
        return canonicalTag in supportedLanguageTags &&
            TranslateLanguage.fromLanguageTag(canonicalTag) != null
    }

    fun resolveConfiguredSource(languageTag: String): String {
        val normalized = normalize(languageTag)
        if (normalized.isEmpty() || normalized == "auto") {
            // Legacy configurations may still contain AUTO. Keep the validation silent so this
            // internal state is never rendered verbatim inside the translation overlay.
            throw TranslationException("")
        }
        return requireSupported(normalized, role = "源")
    }

    fun resolveTarget(languageTag: String): String {
        val normalized = normalize(languageTag)
        if (normalized.isEmpty() || normalized == "auto") {
            throw TranslationException("ML Kit 目标语言必须明确指定，不能使用自动检测")
        }
        return requireSupported(normalized, role = "目标")
    }

    /** ML Kit ships English in the SDK; every other model is language-specific and reusable. */
    fun requiredDownloadLanguages(sourceLanguage: String, targetLanguage: String): Set<String> =
        if (sourceLanguage == targetLanguage) {
            emptySet()
        } else {
            setOf(sourceLanguage, targetLanguage) - TranslateLanguage.ENGLISH
        }

    private fun requireSupported(normalizedTag: String, role: String): String {
        val canonicalTag = canonicalize(normalizedTag)
        if (canonicalTag !in supportedLanguageTags) {
            throw TranslationException("ML Kit 不支持${role}语言: $normalizedTag")
        }
        return TranslateLanguage.fromLanguageTag(canonicalTag)
            ?: throw TranslationException("ML Kit 不支持${role}语言: $normalizedTag")
    }

    private fun canonicalize(normalizedTag: String): String =
        when (normalizedTag) {
            "nb", "nn" -> "no"
            "fil" -> "tl"
            "iw" -> "he"
            "zh-cn", "zh-tw", "zh-hans", "zh-hant" -> "zh"
            else -> normalizedTag.substringBefore('-')
        }

    private fun normalize(languageTag: String): String = languageTag
        .trim()
        .replace('_', '-')
        .lowercase(Locale.ROOT)
}
