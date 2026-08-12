package com.gameocr.app.translate

import android.os.SystemClock
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.llm.LlamaPromptMetrics
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * SakuraLLM Sakura-1.5B Qwen2.5 端侧翻译，**日译中 ACGN/VN/Galgame 专用**。
 *
 * Prompt 取自 SakuraLLM 官方 README 推荐格式（v1.0 起约定，对 Qwen2.5 base 微调）。
 * 与 HY-MT 显著差异：
 * - 显式 system prompt 设定翻译角色，让模型在 ACGN 风格里稳定输出；
 * - 用户消息固定中文指令 "将下面的日文文本翻译成中文"，不接受其它 target 语种；
 * - 选这个引擎时 [Settings.targetLang] 即使设为非 zh-CN 也忽略；非日文源由 RoutingTranslator
 *   在上层做能力检查（看到不匹配的 sourceLang 退到云端引擎或 HY-MT）。
 */
@Singleton
class SakuraGalTranslator @Inject constructor(
    holder: LlamaEngineHolder,
    cache: TranslationCache,
) : LocalLlamaTranslator(holder, cache) {

    override val modelKind = LlmModelKind.SAKURA_1_5B_Q4

    override val prefersBatch: Boolean = true

    override fun batchPromptScope(settings: Settings): BatchPromptScope =
        SakuraBatchPromptScopePolicy.resolve(settings.translationContextMode)

    override val bufferGeneratedOutputUntilValidated: Boolean = true

    override val systemPrompt: String =
        "你是一个轻小说翻译模型，可以流畅通顺地以日本轻小说的风格将日文翻译成简体中文，" +
            "并联系上下文正确使用人称代词，不擅自添加原文中没有的代词。"

    override fun buildUserPrompt(source: String, settings: Settings): String =
        SakuraPromptPolicy.build(
            source = source,
            context = SakuraBatchPromptScopePolicy.promptContext(
                mode = settings.translationContextMode,
                context = settings.runtimeTranslationPromptContext,
            ),
        )

    override fun outputValidation(
        source: String,
        output: String,
        userPrompt: String,
        settings: Settings,
    ): GeneratedOutputValidation {
        val validation = SakuraOutputPolicy.validateLineDetailed(
            source = source,
            output = output,
            forbiddenEchoes = forbiddenEchoes(),
        )
        val reason = "SAKURA:${validation.rejectionReason?.name ?: "UNKNOWN"}"
        return when {
            validation.accepted -> GeneratedOutputValidation.Accepted
            validation.retryable -> GeneratedOutputValidation.Retryable(
                reason = reason,
                fallbackText = source,
            )
            else -> GeneratedOutputValidation.Rejected(reason = reason)
        }
    }

    override fun outputRecoveryPrompt(
        source: String,
        rejectedUserPrompt: String,
        settings: Settings,
        rejection: GeneratedOutputValidation.Invalid,
    ): String? = if (batchPromptScope(settings) == BatchPromptScope.ISOLATED_ITEMS) {
        buildUserPrompt(source, settings)
    } else {
        null
    }

    /** Sakura owns its official prompt contract and must never inherit generic JSON context. */
    override fun runtimePrompt(basePrompt: String, settings: Settings): String = basePrompt

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        if (sources.isEmpty()) return emptyList()
        val budgetedSettings = trimContinuousHistoryForPrompts(
            promptSources = listOf(sources.joinToString("\n")),
            settings = settings,
        )
        val promptScope = batchPromptScope(budgetedSettings)
        Timber.tag(TAG).i(
            "batch route mode=%s promptScope=%s sources=%d",
            budgetedSettings.translationContextMode.name,
            promptScope.name,
            sources.size,
        )
        if (promptScope == BatchPromptScope.ISOLATED_ITEMS) {
            return super.translateBatchIncremental(sources, budgetedSettings, onUpdate)
        }
        if (budgetedSettings.translationContextMode == TranslationContextMode.CONTINUOUS_CONTEXT) {
            val previousTurns = budgetedSettings.runtimeTranslationPromptContext.previousFrame
            Timber.tag(TAG).i(
                "continuous context references previousTurns=%d translatedTurns=%d strategy=sakura-official-glossary",
                previousTurns.size,
                previousTurns.count { !it.translation.isNullOrBlank() },
            )
        }
        val promptTokenBudget = (
            budgetedSettings.localLlmContextSize - budgetedSettings.localLlmMaxNewTokens - CONTEXT_HEADROOM_TOKENS
            ).coerceAtLeast(MINIMUM_PROMPT_TOKEN_BUDGET)
        val groups = holder.withEngineSession(modelKind, systemPrompt) {
            SakuraContextBatchPolicy.groups(
                sources = sources,
                maxPromptTokens = promptTokenBudget,
                promptTokenCount = { joined ->
                    LlamaPromptMetrics.countUserPromptTokens(
                        runtimePrompt(buildUserPrompt(joined, budgetedSettings), budgetedSettings)
                    )
                },
            )
        }
            ?: return super.translateBatchIncremental(sources, budgetedSettings, onUpdate)
        val startedAt = SystemClock.elapsedRealtime()
        val results = MutableList<String?>(sources.size) { null }
        val isolatedRecoveryIndexes = linkedSetOf<Int>()
        groups.forEach { group ->
            translateGroup(
                group = group,
                settings = budgetedSettings,
                results = results,
                startedAt = startedAt,
                onUpdate = onUpdate,
                stage = SakuraRetryStage.INITIAL,
                isolatedRecoveryIndexes = isolatedRecoveryIndexes,
            )
        }
        recoverIsolatedFailures(
            sources = sources,
            originalIndexes = isolatedRecoveryIndexes.toList(),
            settings = budgetedSettings,
            results = results,
            startedAt = startedAt,
            onUpdate = onUpdate,
        )
        return results
    }

    private suspend fun translateGroup(
        group: SakuraContextGroup,
        settings: Settings,
        results: MutableList<String?>,
        startedAt: Long,
        onUpdate: (BatchTranslationUpdate) -> Unit,
        stage: SakuraRetryStage,
        isolatedRecoveryIndexes: MutableSet<Int>,
    ) {
        val prepared = prepareGroups(listOf(group), settings, stage).single()
        val generation = generatePreparedGroups(listOf(prepared), settings)?.singleOrNull()
            ?: generateUncachedResult(
                userPrompt = prepared.userPrompt,
                sourceForLog = group.joinedSource,
                settings = settings,
                mode = prepared.mode,
                predictLength = prepared.generationBudget.effectiveMaxNewTokens,
            )
        processGeneratedGroup(
            prepared = prepared,
            generation = generation,
            settings = settings,
            results = results,
            startedAt = startedAt,
            onUpdate = onUpdate,
            stage = stage,
            isolatedRecoveryIndexes = isolatedRecoveryIndexes,
        )
    }

    private suspend fun processGeneratedGroup(
        prepared: PreparedSakuraGroup,
        generation: GenerationResult,
        settings: Settings,
        results: MutableList<String?>,
        startedAt: Long,
        onUpdate: (BatchTranslationUpdate) -> Unit,
        stage: SakuraRetryStage,
        isolatedRecoveryIndexes: MutableSet<Int>,
    ) {
        val group = prepared.group
        val generationBudget = prepared.generationBudget
        val validation = SakuraOutputPolicy.validateGroup(
            sources = group.sourceLines,
            output = generation.text,
            hitTokenLimit = generation.hitTokenLimit,
            forbiddenEchoes = forbiddenEchoes(),
        )
        validation.lines?.let { lines ->
            val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            val recoveryIndexes = mutableListOf<Int>()
            val preservedIndexes = mutableListOf<Int>()
            lines.forEachIndexed { localIndex, line ->
                val resultIndex = group.startIndex + localIndex
                if (line.accepted) {
                    val text = checkNotNull(line.text)
                    results[resultIndex] = text
                    onUpdate(BatchTranslationUpdate(index = resultIndex, text = text, elapsedMs = elapsedMs))
                } else if (line.retryable && !settings.retryFailedTranslation) {
                    val source = group.sourceLines[localIndex]
                    results[resultIndex] = source
                    preservedIndexes += localIndex
                    onUpdate(BatchTranslationUpdate(index = resultIndex, text = source, elapsedMs = elapsedMs))
                } else {
                    recoveryIndexes += localIndex
                }
            }
            if (preservedIndexes.isNotEmpty()) {
                Timber.tag(TAG).i(
                    "context lines preserved start=%d lines=%d indexes=%s reasons=%s cacheable=false",
                    group.startIndex,
                    group.sourceLines.size,
                    preservedIndexes,
                    preservedIndexes.associateWith { lines[it].rejectionReason?.name },
                )
            }
            if (recoveryIndexes.isEmpty()) return
            Timber.tag(TAG).w(
                "context lines rejected start=%d lines=%d rejected=%s reasons=%s stage=%s retryEnabled=%s",
                group.startIndex,
                group.sourceLines.size,
                recoveryIndexes,
                recoveryIndexes.associateWith { lines[it].rejectionReason?.name },
                stage.name,
                settings.retryFailedTranslation,
            )
            val retryPlan = SakuraRetryPlanPolicy.rejectedLines(
                group = group,
                rejectedLocalIndexes = recoveryIndexes,
                retryEnabled = settings.retryFailedTranslation,
            )
            isolatedRecoveryIndexes += retryPlan.isolatedIndexes
            return
        }

        Timber.tag(TAG).w(
            "context group rejected start=%d expectedLines=%d actualLines=%d outputChars=%d " +
                "pieces=%d hitLimit=%s effectiveMax=%d adaptive=%s reason=%s stage=%s retryEnabled=%s",
            group.startIndex,
            group.sourceLines.size,
            generation.text?.lineSequence()?.count() ?: 0,
            generation.text?.length ?: 0,
            generation.outputPieces,
            generation.hitTokenLimit,
            generationBudget.effectiveMaxNewTokens,
            generationBudget.adaptive,
            validation.rejectionReason?.name,
            stage.name,
            settings.retryFailedTranslation,
        )
        val retryPlan = SakuraRetryPlanPolicy.structuralFailure(
            group = group,
            stage = stage,
            retryEnabled = settings.retryFailedTranslation,
        )
        if (retryPlan.salvageGroups.isNotEmpty()) {
            Timber.tag(TAG).i(
                "context retry plan start=%d lines=%d action=salvage groups=%s",
                group.startIndex,
                group.sourceLines.size,
                retryPlan.salvageGroups.map { it.sourceLines.size },
            )
            translateSalvageGroups(
                groups = retryPlan.salvageGroups,
                settings = settings,
                results = results,
                startedAt = startedAt,
                onUpdate = onUpdate,
                isolatedRecoveryIndexes = isolatedRecoveryIndexes,
            )
        }
        isolatedRecoveryIndexes += retryPlan.isolatedIndexes
    }

    private suspend fun translateSalvageGroups(
        groups: List<SakuraContextGroup>,
        settings: Settings,
        results: MutableList<String?>,
        startedAt: Long,
        onUpdate: (BatchTranslationUpdate) -> Unit,
        isolatedRecoveryIndexes: MutableSet<Int>,
    ) {
        if (groups.isEmpty()) return
        val prepared = prepareGroups(groups, settings, SakuraRetryStage.SALVAGE)
        val generations = generatePreparedGroups(prepared, settings)
        if (generations == null) {
            groups.forEach { group ->
                translateGroup(
                    group = group,
                    settings = settings,
                    results = results,
                    startedAt = startedAt,
                    onUpdate = onUpdate,
                    stage = SakuraRetryStage.SALVAGE,
                    isolatedRecoveryIndexes = isolatedRecoveryIndexes,
                )
            }
            return
        }
        prepared.zip(generations).forEach { (group, generation) ->
            processGeneratedGroup(
                prepared = group,
                generation = generation,
                settings = settings,
                results = results,
                startedAt = startedAt,
                onUpdate = onUpdate,
                stage = SakuraRetryStage.SALVAGE,
                isolatedRecoveryIndexes = isolatedRecoveryIndexes,
            )
        }
    }

    private suspend fun prepareGroups(
        groups: List<SakuraContextGroup>,
        settings: Settings,
        stage: SakuraRetryStage,
    ): List<PreparedSakuraGroup> {
        val sourceTokens = holder.withEngineSession(modelKind, systemPrompt) {
            groups.map { LlamaPromptMetrics.countTextTokens(it.joinedSource) }
        }
        return groups.mapIndexed { index, group ->
            val generationBudget = SakuraGenerationBudgetPolicy.decide(
                configuredMaxNewTokens = settings.localLlmMaxNewTokens,
                sourceTokens = sourceTokens[index],
                lineCount = group.sourceLines.size,
            )
            Timber.tag(TAG).i(
                "generation budget start=%d lines=%d sourceTokens=%d configuredMax=%d effectiveMax=%d adaptive=%s",
                group.startIndex,
                group.sourceLines.size,
                generationBudget.sourceTokens,
                generationBudget.configuredMaxNewTokens,
                generationBudget.effectiveMaxNewTokens,
                generationBudget.adaptive,
            )
            PreparedSakuraGroup(
                group = group,
                userPrompt = buildUserPrompt(group.joinedSource, settings),
                mode = "sakura-context-${group.sourceLines.size}-${stage.name.lowercase()}",
                generationBudget = generationBudget,
            )
        }
    }

    private suspend fun generatePreparedGroups(
        prepared: List<PreparedSakuraGroup>,
        settings: Settings,
    ): List<GenerationResult>? = generateUncachedBatchResults(
        requests = prepared.map { group ->
            UncachedGenerationRequest(
                userPrompt = group.userPrompt,
                sourceForLog = group.group.joinedSource,
                mode = group.mode,
                predictLength = group.generationBudget.effectiveMaxNewTokens,
                maxOutputLines = group.group.sourceLines.size,
            )
        },
        settings = settings,
        markLimitAsInvalid = true,
    )

    private suspend fun recoverIsolatedFailures(
        sources: List<String>,
        originalIndexes: List<Int>,
        settings: Settings,
        results: MutableList<String?>,
        startedAt: Long,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ) {
        val pendingIndexes = originalIndexes
            .asSequence()
            .filter { it in sources.indices && results[it] == null }
            .distinct()
            .toList()
        if (!settings.retryFailedTranslation || pendingIndexes.isEmpty()) return

        Timber.tag(TAG).i(
            "isolated recovery begin items=%d indexes=%s strategy=native-independent-batch",
            pendingIndexes.size,
            pendingIndexes,
        )
        val recoveryStartedAt = SystemClock.elapsedRealtime()
        val recoveryOffsetMs = (recoveryStartedAt - startedAt).coerceAtLeast(0L)
        val recovered = super.translateBatchIncremental(
            sources = pendingIndexes.map(sources::get),
            settings = settings.copy(retryFailedTranslation = false),
            onUpdate = { update ->
                pendingIndexes.getOrNull(update.index)?.let { originalIndex ->
                    onUpdate(
                        update.copy(
                            index = originalIndex,
                            elapsedMs = update.elapsedMs?.let { recoveryOffsetMs + it }
                                ?: (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                        )
                    )
                }
            },
        )
        pendingIndexes.forEachIndexed { localIndex, originalIndex ->
            results[originalIndex] = recovered.getOrNull(localIndex)
        }
        Timber.tag(TAG).i(
            "isolated recovery end items=%d recovered=%d failed=%d totalMs=%d",
            pendingIndexes.size,
            recovered.count { !it.isNullOrBlank() },
            pendingIndexes.size - recovered.count { !it.isNullOrBlank() },
            (SystemClock.elapsedRealtime() - recoveryStartedAt).coerceAtLeast(0L),
        )
    }

    private fun forbiddenEchoes(): List<String> = listOf(
        systemPrompt,
        SakuraPromptPolicy.BASIC_INSTRUCTION,
        SakuraPromptPolicy.GLOSSARY_HEADER,
        SakuraPromptPolicy.GLOSSARY_INSTRUCTION,
    )

    private data class PreparedSakuraGroup(
        val group: SakuraContextGroup,
        val userPrompt: String,
        val mode: String,
        val generationBudget: SakuraGenerationBudget,
    )

    private companion object {
        const val TAG = "SakuraContextBatch"
        const val CONTEXT_HEADROOM_TOKENS = 64
        const val MINIMUM_PROMPT_TOKEN_BUDGET = 64
    }
}
