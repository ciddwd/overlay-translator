package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlmModelKind
import javax.inject.Inject
import javax.inject.Singleton

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

    override val systemPrompt: String =
        "你是一个轻小说翻译模型，可以流畅通顺地以日本轻小说的风格将日文翻译成简体中文，" +
            "并联系上下文正确使用人称代词，不擅自添加原文中没有的代词。"

    override fun buildUserPrompt(source: String, settings: Settings): String =
        "将下面的日文文本翻译成中文：$source"
}
