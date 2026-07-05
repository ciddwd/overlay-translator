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
    );

    enum class TargetLangMode {
        /** 跟 Settings 里 targetLang 走（多语种翻译模型用）。当前无此模式枚举值的 kind。 */
        FOLLOWS_SETTINGS,
        /** 锁定中文（Sakura 训练目标）。 */
        FIXED_ZH_CN,
    }
}
