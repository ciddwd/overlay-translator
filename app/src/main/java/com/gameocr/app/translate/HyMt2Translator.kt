package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.data.Languages
import com.gameocr.app.data.Settings
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlmModelKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tencent Hy-MT2-1.8B Q4_K_M 端侧多语种翻译。
 *
 * Hy-MT2 是翻译专用模型，不需要 Sakura 那种 ACGN system prompt。这里用短指令把源/目标语言
 * 显式塞进 user prompt，并要求只输出译文，便于覆盖屏译的多语言设置。
 */
@Singleton
class HyMt2Translator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    holder: LlamaEngineHolder,
    cache: TranslationCache,
) : LocalLlamaTranslator(holder, cache) {

    override val modelKind = LlmModelKind.HY_MT2_1_8B_Q4_K_M

    override val systemPrompt: String? = null

    override fun buildUserPrompt(source: String, settings: Settings): String {
        val sourceName = sourceDisplayName(appContext, settings.sourceLang)
        val targetName = targetDisplayName(appContext, settings.targetLang)
        return "Translate the following $sourceName text into $targetName. " +
            "Output only the translation, without explanations or quotes.\n\n$source"
    }

    internal companion object {
        fun sourceDisplayName(context: Context, sourceLang: String): String {
            val normalized = sourceLang.trim()
            return if (normalized.equals("auto", ignoreCase = true) || normalized.isBlank()) {
                "source-language"
            } else {
                Languages.nameOf(context, normalized)
            }
        }

        fun targetDisplayName(context: Context, targetLang: String): String {
            return Languages.nameOf(context, normalizeTargetLang(targetLang))
        }

        fun normalizeTargetLang(targetLang: String): String {
            val normalized = targetLang.trim()
            return if (normalized.equals("auto", ignoreCase = true) || normalized.isBlank()) {
                "zh-CN"
            } else {
                normalized
            }
        }
    }
}
