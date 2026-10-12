package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站 Index-Translate 免费公网翻译 API（OpenAI 兼容 Chat Completions）。
 *
 * 与 [GoogleTranslator] 一样是"开箱即用"的独立引擎入口：baseUrl / apiKey / model
 * 全部内置写死，用户无需任何配置。管线整体委托 [OpenAiTranslator] 复用其 OpenAI 兼容
 * 请求、流式、批量、划词与 testConnection（真实 chat 请求判定 + 成功后附加模型列表）。
 */
@Singleton
class BilibiliIndexTranslator @Inject constructor(
    private val openAi: OpenAiTranslator,
) : Translator {

    companion object {
        /** B站 Index-Translate 免费公网端点（OpenAI 兼容，以 `/v1/` 结尾）。 */
        const val BASE_URL = "https://index-translate.bilibili.com/v1/"
        /** 该公网 API 的固定访问 key。 */
        const val API_KEY = "index"
        /** 内置模型名。 */
        const val MODEL = "Index-Translate-35B-A3B"
    }

    /** 把当前 Settings 替换为 B站内置参数，其余（语种/提示词/超时等）沿用用户配置。 */
    private fun biliSettings(settings: Settings): Settings =
        settings.copy(baseUrl = BASE_URL, apiKey = API_KEY, model = MODEL)

    override suspend fun translate(source: String, settings: Settings): String? =
        openAi.translate(source, biliSettings(settings))

    override fun translateStream(source: String, settings: Settings): Flow<String> =
        openAi.translateStream(source, biliSettings(settings))

    override val supportsStructuredContextBatch: Boolean
        get() = openAi.supportsStructuredContextBatch

    override fun handlesTranslationFailureRetry(settings: Settings): Boolean =
        openAi.handlesTranslationFailureRetry(biliSettings(settings))

    override fun batchPromptScope(settings: Settings): BatchPromptScope =
        openAi.batchPromptScope(biliSettings(settings))

    override suspend fun translateBatch(
        sources: List<String>,
        settings: Settings,
    ): List<String?> = openAi.translateBatch(sources, biliSettings(settings))

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> = openAi.translateBatchIncremental(sources, biliSettings(settings), onUpdate)

    override suspend fun translateWord(source: String, settings: Settings): WordResult? =
        openAi.translateWord(source, biliSettings(settings))

    override suspend fun translateWordCompact(source: String, settings: Settings): WordResult? =
        openAi.translateWordCompact(source, biliSettings(settings))

    override suspend fun testConnection(settings: Settings): TestResult =
        openAi.testConnection(biliSettings(settings))
}
