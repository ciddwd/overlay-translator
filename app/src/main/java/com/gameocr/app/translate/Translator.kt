package com.gameocr.app.translate

import android.graphics.Bitmap
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextBlock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

data class BatchTranslationUpdate(
    val index: Int,
    val text: String?,
    val elapsedMs: Long? = null,
)

/** Whether items submitted through a batch call can observe one another's source text. */
enum class BatchPromptScope {
    /** Items may share transport or a native decode loop, but each item owns an isolated prompt. */
    ISOLATED_ITEMS,

    /** Every source must remain available because the engine translates them through a shared page prompt. */
    SHARED_PAGE,
}

internal class BatchTranslationProgressState(size: Int) {
    private val emitted = BooleanArray(size.coerceAtLeast(0))

    fun accept(index: Int): Boolean {
        if (index !in emitted.indices || emitted[index]) return false
        emitted[index] = true
        return true
    }

    fun isEmitted(index: Int): Boolean = index in emitted.indices && emitted[index]

    fun pendingIndexes(): List<Int> = emitted.indices.filterNot { emitted[it] }
}

interface Translator {
    /**
     * 翻译一段文本（非流式）。失败抛异常。返回 null 表示空输入。
     */
    suspend fun translate(source: String, settings: Settings): String?

    /**
     * 流式翻译。每次发射当前已累积的完整译文（便于 UI 一次性 setText 全量更新）。
     * 失败抛异常；空输入返回空 Flow。
     */
    fun translateStream(source: String, settings: Settings): Flow<String>

    /**
     * 该引擎是否倾向批处理：true 表示 CaptureService 应优先用 [translateBatch] 把一帧多段
     * 合并为单次 API 调用（如 DeepL 免费档限频严格，必须批处理）。false 表示该引擎在配额
     * 上不需要批处理，且对流式体验敏感（如 OpenAI 兼容 LLM 用户依赖逐 token 流式）。
     */
    val prefersBatch: Boolean get() = false

    /**
     * 描述批量项目之间的 Prompt 可见性，而不是传输或执行形态。即使多个独立序列共用一次
     * decode loop，只要彼此不可见，仍属于 [BatchPromptScope.ISOLATED_ITEMS]。
     */
    fun batchPromptScope(settings: Settings): BatchPromptScope = BatchPromptScope.ISOLATED_ITEMS

    /**
     * true means the engine can translate a whole contextual page in one structured request while
     * preserving a stable source-to-result mapping. CaptureService only enables this capability for
     * PAGE_CONTEXT / CONTINUOUS_CONTEXT; FAST_PER_SEGMENT keeps the existing streaming behavior.
     */
    val supportsStructuredContextBatch: Boolean get() = false

    /**
     * True when this engine applies the user-controlled translation failure retry internally.
     * Callers must not add another retry after a null result from such an engine.
     */
    fun handlesTranslationFailureRetry(settings: Settings): Boolean = false

    /**
     * 批量翻译。默认实现是并发调单条 [translate]；引擎若支持原生批 API 应 override 用单
     * 次 HTTP 处理多段（DeepL 的 v2/translate 支持 form 里多个 `text` 参数即此目的）。
     *
     * 返回值列表长度与 [sources] 一致，索引一一对应；某条失败或空输入用 null 占位，不影响
     * 其它项。
     */
    suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> {
        if (sources.isEmpty()) return emptyList()
        return coroutineScope {
            sources.map { src ->
                async { runCatching { translate(src, settings) }.getOrNull() }
            }.awaitAll()
        }
    }

    /**
     * Batch translation with completed-item notifications. The callback is deliberately
     * non-suspending: native inference may invoke it while holding the engine mutex, so callers
     * must enqueue UI work and return immediately. Engines without incremental batching retain
     * the old behavior and emit all items after [translateBatch] completes.
     */
    suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        val startedAtNs = System.nanoTime()
        val results = translateBatch(sources, settings)
        val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L)
        results.forEachIndexed { index, text ->
            onUpdate(
                BatchTranslationUpdate(
                    index = index,
                    text = text,
                    elapsedMs = elapsedMs,
                )
            )
        }
        return results
    }

    /**
     * 连通性测试。用于设置页"测试连接"按钮：验证 key/baseUrl/model 配置可用，并尽可能
     * 顺带返回有用信息（DeepL：剩余字符额度；OpenAI 兼容：拉到的 model 列表）。
     *
     * 实现约定：不抛异常——任何失败都包装成 [TestResult](success=false, message=...)。
     */
    suspend fun testConnection(settings: Settings): TestResult =
        TestResult(false, "testConnection not implemented")

    /**
     * 端到端引擎能力：true 表示该 Translator 既能 OCR 又能翻译，CaptureService 应跳过
     * [com.gameocr.app.ocr.OcrEngine] 阶段，直接调 [ocrAndTranslate]。
     *
     * 目前只有有道图片翻译（YOUDAO_PICTRANS）走这条路。
     */
    val isEndToEnd: Boolean get() = false

    /**
     * 端到端"OCR + 翻译"。仅 [isEndToEnd] = true 的引擎实现；其它默认抛 [NotImplementedError]
     * 不会被 CaptureService 调用到（流程会先判 isEndToEnd 走原 OCR + translate 路径）。
     *
     * 返回 List<Pair<TextBlock, String>>：每段原文 box + 对应译文，box 坐标系跟现有 OCR
     * 引擎一致（屏幕像素，未做 upscale 还原由调用方处理）。
     */
    suspend fun ocrAndTranslate(bitmap: Bitmap, settings: Settings): List<Pair<TextBlock, String>> =
        throw NotImplementedError("ocrAndTranslate not supported by this translator")

    /**
     * 划词翻译专用：把输入当**单词 / 短语**走字典化 prompt，返回 [WordResult]（音标 / 词性 / 释义
     * / 词形变化 / 同义词 / 难点解释 / 例句）。默认返回 null 表示「本引擎不支持词典化」，
     * 调用方应回退到 [translate]。
     *
     * 仅 OpenAI 兼容引擎实现：通过 [Settings.dictionaryPrompt] 让 LLM 返回 JSON，解析失败也返
     * 回 null（CaptureService 看到 null 就走纯翻译）。DeepL / 百度 / 腾讯 / Google / 火山 / 有道
     * 都没有词典 API，全部走默认实现。
     */
    suspend fun translateWord(source: String, settings: Settings): WordResult? = null

    /**
     * Compact dictionary lookup used by tap-to-preview surfaces. Implementations should only
     * request a lemma and grouped part-of-speech meanings so the preview can appear quickly.
     * Full dictionary details remain the responsibility of [translateWord].
     */
    suspend fun translateWordCompact(source: String, settings: Settings): WordResult? = null
}

/**
 * 划词翻译返回结构化数据。任何字段缺失用空串 / 空数组占位，UI 卡片按非空分段渲染。
 *
 * - [phonetic]：单词读音 / 音标（源语言）；CJK 用罗马音或汉语拼音
 * - [senses]：首选结构；每个词性与只属于它的释义保持在同一项
 * - [pos] / [definitions]：兼容旧模型响应的扁平字段，不得按数组下标猜测对应关系
 * - [inflections]：源语言中常见的词形变化，如过去式、过去分词、复数或比较级
 * - [synonyms]：源语言同义词或近义词
 * - [difficultyNotes]：生僻词、专业术语、缩写或易混淆用法的难点解释；普通词留空
 * - [examples]：例句对，最多 2 条
 * - [fallbackTranslation]：当 LLM 拒绝词典化（比如选中其实是短句）时给的纯翻译兜底
 */
data class WordResult(
    val phonetic: String = "",
    val pos: List<String> = emptyList(),
    val definitions: List<String> = emptyList(),
    val inflections: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val difficultyNotes: List<String> = emptyList(),
    val examples: List<ExamplePair> = emptyList(),
    val fallbackTranslation: String? = null,
    /** Canonical source-language lemma, for example `display` for `displayed`. */
    val lemma: String = "",
    /** Meanings grouped with the part of speech they belong to. */
    val senses: List<WordSense> = emptyList(),
) {
    /** 任何字典字段都为空 → 等价于纯翻译失败。 */
    fun isEmpty(): Boolean = phonetic.isBlank() && pos.isEmpty() &&
        senses.none { sense -> sense.definitions.any(String::isNotBlank) } &&
        definitions.isEmpty() && inflections.isEmpty() && synonyms.isEmpty() &&
        difficultyNotes.isEmpty() && examples.isEmpty()

    fun effectiveDefinitions(): List<String> = senses
        .mapNotNull(WordSense::normalizedOrNull)
        .flatMap(WordSense::definitions)
        .takeIf(List<String>::isNotEmpty)
        ?: definitions

    fun effectivePartsOfSpeech(): List<String> = senses
        .mapNotNull(WordSense::normalizedOrNull)
        .map(WordSense::partOfSpeech)
        .filter(String::isNotBlank)
        .distinct()
        .takeIf(List<String>::isNotEmpty)
        ?: pos

    /**
     * Legacy responses are grouped only when there is exactly one part of speech. Pairing two
     * unrelated arrays by index would silently attach meanings to the wrong grammatical role.
     */
    fun effectiveSenses(): List<WordSense> = senses
        .mergeByPartOfSpeech()
        .takeIf(List<WordSense>::isNotEmpty)
        ?: if (pos.size == 1 && definitions.isNotEmpty()) {
            listOf(WordSense(partOfSpeech = pos.single(), definitions = definitions))
                .mergeByPartOfSpeech()
        } else {
            emptyList()
        }
}

data class WordSense(
    val partOfSpeech: String = "",
    val definitions: List<String> = emptyList(),
    val formNote: String = "",
) {
    internal fun normalizedOrNull(): WordSense? {
        val normalizedDefinitions = definitions
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (normalizedDefinitions.isEmpty()) return null
        return copy(
            partOfSpeech = partOfSpeech.trim(),
            definitions = normalizedDefinitions,
            formNote = formNote.trim(),
        )
    }
}

/**
 * Models sometimes split one grammatical role into several JSON sense objects. Merge only senses
 * whose normalized part-of-speech labels are equal; meanings from different roles never cross.
 */
internal fun Iterable<WordSense>.mergeByPartOfSpeech(): List<WordSense> {
    data class SenseAccumulator(
        val partOfSpeech: String,
        val definitions: LinkedHashSet<String> = linkedSetOf(),
        val formNotes: LinkedHashSet<String> = linkedSetOf(),
    )

    val grouped = linkedMapOf<String, SenseAccumulator>()
    for (sense in this) {
        val normalized = sense.normalizedOrNull() ?: continue
        val displayPartOfSpeech = normalized.partOfSpeech.replace(POS_WHITESPACE, " ")
        val key = displayPartOfSpeech.lowercase()
        val accumulator = grouped.getOrPut(key) {
            SenseAccumulator(partOfSpeech = displayPartOfSpeech)
        }
        accumulator.definitions.addAll(normalized.definitions)
        normalized.formNote.takeIf(String::isNotBlank)?.let(accumulator.formNotes::add)
    }
    return grouped.values.map { accumulator ->
        WordSense(
            partOfSpeech = accumulator.partOfSpeech,
            definitions = accumulator.definitions.toList(),
            formNote = accumulator.formNotes.joinToString("；"),
        )
    }
}

private val POS_WHITESPACE = Regex("\\s+")

data class ExamplePair(val src: String, val dst: String)

/**
 * 翻译引擎连通性测试结果。
 *
 * @property success true=可用
 * @property message 给用户看的简短文案（成功如"OK · 已用 X / Y 字符"；失败如"HTTP 401: ..."）
 * @property models OpenAI 兼容 `GET /v1/models` 拉到的 id 列表，便于 UI 让用户从下拉中选；
 *                  DeepL / 失败 / 模型探活模式都返回空列表
 */
data class TestResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList()
)

class TranslationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
