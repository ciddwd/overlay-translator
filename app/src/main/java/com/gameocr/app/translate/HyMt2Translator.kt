package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlmModelKind
import javax.inject.Inject
import javax.inject.Singleton

/** Tencent Hy-MT2-1.8B Q4_K_M on-device translator using the official user templates. */
@Singleton
class HyMt2Translator @Inject constructor(
    holder: LlamaEngineHolder,
    cache: TranslationCache,
) : LocalLlamaTranslator(holder, cache) {

    override val modelKind = LlmModelKind.HY_MT2_1_8B_Q4_K_M

    override val systemPrompt: String? = null

    override val bufferGeneratedOutputUntilValidated: Boolean = true

    override fun buildUserPrompt(source: String, settings: Settings): String =
        HyMt2PromptPolicy.build(
            source = source,
            targetLang = settings.targetLang,
            context = settings.runtimeTranslationPromptContext,
        )

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> = super.translateBatchIncremental(
        sources = sources,
        settings = trimContinuousHistoryForPrompts(sources, settings),
        onUpdate = onUpdate,
    )

    /** Hy-MT2 already renders request context into its official structured template. */
    override fun runtimePrompt(basePrompt: String, settings: Settings): String = basePrompt

    override fun outputValidation(
        source: String,
        output: String,
        userPrompt: String,
        settings: Settings,
    ): GeneratedOutputValidation = when (
        val decision = HyMt2OutputPolicy.inspect(
            source = source,
            output = output,
            requestHadBackground = HyMt2PromptPolicy.isBackgroundPrompt(userPrompt),
        )
    ) {
        HyMt2OutputPolicy.Decision.Accept -> GeneratedOutputValidation.Accepted
        is HyMt2OutputPolicy.Decision.Reject -> GeneratedOutputValidation.Rejected(
            reason = "HY_MT2_CONTEXT_BLEED:${decision.reason.name}",
        )
    }

    override fun normalizeGeneratedOutput(
        source: String,
        output: String,
        userPrompt: String,
        settings: Settings,
    ): String = HyMt2OutputPolicy.normalizeHarmlessSingleItemPrefix(
        source = source,
        output = output,
        requestHadBackground = HyMt2PromptPolicy.isBackgroundPrompt(userPrompt),
    )

    override fun outputRecoveryPrompt(
        source: String,
        rejectedUserPrompt: String,
        settings: Settings,
        rejection: GeneratedOutputValidation.Invalid,
    ): String? {
        if (!HyMt2PromptPolicy.isBackgroundPrompt(rejectedUserPrompt)) return null
        return HyMt2PromptPolicy.buildWithoutBackground(
            source = source,
            targetLang = settings.targetLang,
            context = settings.runtimeTranslationPromptContext,
        )
    }

    override fun nativeBatchMaxOutputLines(
        source: String,
        userPrompt: String,
        settings: Settings,
    ): Int = HyMt2GenerationBoundaryPolicy.maxOutputLines(
        source = source,
        requestHadBackground = HyMt2PromptPolicy.isBackgroundPrompt(userPrompt),
    )

    override val markNativeBatchLineLimitAsInvalid: Boolean = true

    internal companion object {
        fun normalizeTargetLang(targetLang: String): String =
            HyMt2PromptPolicy.normalizeTargetLang(targetLang)
    }
}
