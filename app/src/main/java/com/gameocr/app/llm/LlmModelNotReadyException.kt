package com.gameocr.app.llm

/**
 * 端侧 LLM 模型尚未下载 / 校验失败。UI 层（SettingsScreen 或翻译结果浮卡）捕获后，
 * 引导用户去设置页"端侧 LLM 翻译"区块完成下载。
 *
 * 对标 [com.gameocr.app.ocr.ModelNotReadyException]。
 */
class LlmModelNotReadyException(message: String) : Exception(message)
