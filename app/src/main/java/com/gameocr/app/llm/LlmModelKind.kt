package com.gameocr.app.llm

/**
 * 端侧 LLM 翻译模型的元数据。每个 kind 对应一个 GGUF 权重文件：
 * - 名称、文件名、ModelScope/HF 下载链接
 * - 大概体积（MB），用于 UI 提示与流量预警
 * - 限定可用的源/目标语言（Sakura 仅 日译中；Hy-MT2 跟随设置）
 *
 * 只接主线 llama.cpp 可识别的 GGUF。Hy-MT2 的 2bit / 1.25bit AngelSlim 量化仍依赖未合主线
 * 的 PR，这里先接 Q4_K_M，避免用户下载后加载失败。
 */
enum class LlmModelKind(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val approxSizeMb: Int,
    val targetLangMode: TargetLangMode,
    val onlySourceLangBcp47: String? = null,
    val quantization: String = "Q4_K_M",
) {
    /**
     * SakuraLLM 的 Qwen2.5-1.5B v1.0 微调版（Q5KS imatrix 量化），日译中 ACGN/VN/Galgame 专用。
     *
     * 官方 SakuraLLM/Sakura-1.5B-Qwen2.5-v1.0-GGUF 只发了 fp16（3.56 GB）—— 端侧不可行。
     * 改用社区 shing3232/Sakura-1.5B-Qwen2.5-v1.0-GGUF-IMX 的 Q5KS（1.26 GB）：
     * imatrix 校准的 Q5_K_S，质量接近 fp16，体积可控。
     *
     * **枚举名保留为 SAKURA_1_5B_Q4** 以维持代码引用稳定性（实际是 Q5KS，与 settings 字符串
     * "LOCAL_SAKURA" 无关；改 fileName 与 displayName 已经反映真实量化）。
     */
    SAKURA_1_5B_Q4(
        displayName = "Sakura-1.5B Qwen2.5 (Q5KS)",
        fileName = "sakura-1.5b-qwen2.5-v1.0-Q5KS.gguf",
        downloadUrl = "https://huggingface.co/shing3232/Sakura-1.5B-Qwen2.5-v1.0-GGUF-IMX/resolve/main/sakura-1.5b-qwen2.5-v1.0-Q5KS.gguf",
        approxSizeMb = 1260,
        targetLangMode = TargetLangMode.FIXED_ZH_CN,
        onlySourceLangBcp47 = "ja",
        quantization = "Q5_K_S",
    ),

    /**
     * 腾讯 Hy-MT2-1.8B 多语种翻译模型的官方 GGUF Q4_K_M 量化版。
     *
     * 官方仓库还提供 Q6_K / Q8_0，以及 2bit / 1.25bit AngelSlim 版本。后两者当前仍依赖
     * 未合主线的 llama.cpp kernel；Q4_K_M 是体积、兼容性和翻译能力之间更稳的端侧首选。
     */
    HY_MT2_1_8B_Q4_K_M(
        displayName = "Hy-MT2-1.8B (Q4_K_M)",
        fileName = "Hy-MT2-1.8B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_K_M.gguf",
        approxSizeMb = 1130,
        targetLangMode = TargetLangMode.FOLLOWS_SETTINGS,
    ),

    /** Official Qwen3.5-based multilingual translation weights, downloaded on demand. */
    INDEX_TRANSLATE_2B_Q4_K_M(
        displayName = "Index-Translate-2B (Q4_K_M)",
        fileName = "Index-Translate-2B.Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/main/Index-Translate-2B.Q4_K_M.gguf",
        approxSizeMb = 1310,
        targetLangMode = TargetLangMode.FOLLOWS_SETTINGS,
    ),
    QWEN3_0_6B_Q8_0(
        displayName = "Qwen3-0.6B (Q8_0)",
        fileName = "Qwen3-0.6B-Q8_0.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
        approxSizeMb = 639,
        targetLangMode = TargetLangMode.FOLLOWS_SETTINGS,
        quantization = "Q8_0",
    ),
    LFM2_5_1_2B_INSTRUCT_Q4_K_M(
        "LFM2.5-1.2B-Instruct", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        697, TargetLangMode.FOLLOWS_SETTINGS,
    ),
    GEMMA4_E2B_Q4_0(
        "Gemma-4-E2B-it", "gemma-4-E2B_q4_0-it.gguf",
        "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf",
        3194, TargetLangMode.FOLLOWS_SETTINGS, quantization = "Q4_0",
    ),
    GEMMA4_E4B_Q4_0(
        "Gemma-4-E4B-it", "gemma-4-E4B_q4_0-it.gguf",
        "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B_q4_0-it.gguf",
        4916, TargetLangMode.FOLLOWS_SETTINGS, quantization = "Q4_0",
    ),
    GEMMA3_4B_Q4_K_M(
        "Gemma-3-4B-it", "gemma-3-4b-it-Q4_K_M.gguf",
        "https://huggingface.co/ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
        2374, TargetLangMode.FOLLOWS_SETTINGS,
    ),
    TRANSLATEGEMMA_4B_Q4_K_M(
        "TranslateGemma-4B-it", "translategemma-4b_Q4_K_M.gguf",
        "https://huggingface.co/SandLogicTechnologies/translategemma-4b-it-GGUF/resolve/main/translategemma-4b_Q4_K_M.gguf",
        2375, TargetLangMode.FOLLOWS_SETTINGS,
    );

    val usesGgufTemplate: Boolean get() = this != SAKURA_1_5B_Q4 && this != HY_MT2_1_8B_Q4_K_M

    /** These presets share the generic engine but keep their own stable file/configuration ID. */
    val managedViaGeneric: Boolean get() = this != SAKURA_1_5B_Q4 &&
        this != HY_MT2_1_8B_Q4_K_M && this != INDEX_TRANSLATE_2B_Q4_K_M

    /**
     * Translation-tuned models keep their own prompt and sampling; user prompt and sampling
     * overrides apply only to general models (this preset and every imported GGUF).
     */
    val specialized: Boolean get() = this == SAKURA_1_5B_Q4 || this == HY_MT2_1_8B_Q4_K_M ||
        this == INDEX_TRANSLATE_2B_Q4_K_M || this == TRANSLATEGEMMA_4B_Q4_K_M

    companion object {
        fun fromBuiltInId(id: String?): LlmModelKind? =
            id?.takeIf { it.startsWith("builtin:") }?.removePrefix("builtin:")?.let { name ->
                entries.firstOrNull { it.name == name }
            }
    }

    enum class TargetLangMode {
        /** 跟 Settings 里 targetLang 走（多语种翻译模型用）。当前无此模式枚举值的 kind。 */
        FOLLOWS_SETTINGS,
        /** 锁定中文（Sakura 训练目标）。 */
        FIXED_ZH_CN,
    }
}
