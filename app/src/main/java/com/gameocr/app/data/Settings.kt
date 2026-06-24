package com.gameocr.app.data

import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureRegion
import kotlinx.serialization.Serializable

/** 用户配置：OCR / 翻译后端相关。 */
@Serializable
data class Settings(
    val baseUrl: String = "https://api.deepseek.com/v1/",
    val apiKey: String = "",
    val model: String = "deepseek-v4-flash",
    /** BCP-47 源语言代码（如 "auto"/"ja"/"zh-CN"）。从全部 [Languages.ALL] 中选取。 */
    val sourceLang: String = Languages.AUTO.code,
    val targetLang: String = "zh-CN",
    val promptTemplate: String = DEFAULT_PROMPT,
    val ocrEngine: OcrEngineKind = OcrEngineKind.ML_KIT_AUTO,
    val captureLoopIntervalMs: Long = 1000L,
    val captureRegion: CaptureRegion? = null,
    val overlayTextSizeSp: Int = 14,
    val overlayAlpha: Float = 0.85f,
    val streamingTranslate: Boolean = true,
    val renderMode: RenderMode = RenderMode.BLOCKS,
    val overlayPlacement: OverlayPlacement = OverlayPlacement.OVERLAP,
    val overlayTheme: OverlayTheme = OverlayTheme.CLASSIC_DARK,
    /** CUSTOM 主题用：ARGB int，比如 0xE6000000.toInt() 半透明黑。 */
    val customBgColor: Int = 0xE6000000.toInt(),
    val customFgColor: Int = 0xFFFFFFFF.toInt(),
    val customBorderColor: Int = 0x00000000,
    /** 边框粗细（dp，0=无边）。 */
    val customBorderWidth: Int = 0,
    /** 译文相对原文 boundingBox 的水平偏移（px，负数=往左，正数=往右）。 */
    val overlayOffsetX: Int = 0,
    /** 译文相对原文 boundingBox 的垂直额外偏移（px，叠加到 placement 计算结果之上）。 */
    val overlayOffsetY: Int = 0,
    val preprocess: PreprocessOptions = PreprocessOptions(),
    val baiduOcrApiKey: String = "",
    val baiduOcrSecretKey: String = "",
    /** 百度 OCR 接口类型。默认含位置标准版，能让译文紧贴原文 boundingBox 渲染。 */
    val baiduOcrEndpoint: BaiduOcrEndpoint = BaiduOcrEndpoint.GENERAL,
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val tencentRegion: String = "ap-guangzhou",
    /** 腾讯云 OCR 接口类型。三种选择各自有独立配额、价格、识别能力。 */
    val tencentOcrEndpoint: TencentOcrEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
    val paddleModelMirrorUrl: String = "",
    val preferShizukuCapture: Boolean = false,
    val a11yVolumeTrigger: Boolean = false,
    val translatorEngine: TranslatorEngine = TranslatorEngine.OPENAI,
    val deeplApiKey: String = "",
    val deeplPro: Boolean = false,
    /** 悬浮按钮直径（dp）。 */
    val floatingButtonSizeDp: Int = 40,
    /** 译文允许换行（关闭后强制单行，可能横向溢出但更紧凑）。 */
    val overlayAllowWrap: Boolean = true,
    /** 启用碰撞检测：上下左右四个方向都避免遮挡其它原文 box。 */
    val overlayAvoidCollision: Boolean = true,
    /**
     * API 请求超时（秒），同时作用于 OCR（百度 / 腾讯）和翻译（OpenAI / DeepL）。
     * connect/read/write/call 都用这个值（call 是总超时上限）。
     * 模型下载（PaddleOCR 模型 ~20MB）不受这个限制，走默认 60s 的下载 client。
     */
    val apiTimeoutSeconds: Int = 30,
    /**
     * OCR 后合并相邻 box：把同一行内左右邻接的小 box 合并成一个，文本用空格拼接，
     * box 取 union。漫画 / 字幕场景百度等引擎经常把一句话拆成多段，开启后能让译文
     * 不再分裂成多个互相重叠的小框。默认开。
     */
    val mergeAdjacentBlocks: Boolean = false,
    /**
     * 用户在 LanguagePicker 里星标过的语言代码，按收藏顺序保存。
     * 列表里在最前，源语言 / 目标语言两个选择器共享同一份。
     */
    val pinnedLanguages: List<String> = emptyList()
) {
    companion object {
        /**
         * 默认 prompt 用占位符 `{source}` / `{target}`，运行时替换为当前 source/target 语言名称。
         * 这样用户在设置里改语言 chip 后无需重写 prompt。
         *
         * 注意：本常量仅作为 [Settings.promptTemplate] 的兜底默认值，跟随中文（i18n 后 prompt 仍按
         * 中文 prompt 工作良好——多数 LLM 对中文 prompt 同样理解输出指定语言）。UI 里"恢复默认
         * prompt"按钮也用此值。如果将来要做 prompt 本地化，把这里改成根据 context 读 R.string.default_prompt。
         */
        const val DEFAULT_PROMPT: String = """你是一名专业的译者，把下面的{source}原文翻译成{target}。要求：
1. 保留人名、地名等专有名词；
2. 自然流畅，避免直译腔；
3. 只输出译文，不加解释、不加引号。
原文：
"""
    }
}

@Serializable
enum class TranslatorEngine {
    /** OpenAI 兼容 LLM（DeepSeek / SiliconFlow / GPT / 自架 Ollama 等）。 */
    OPENAI,
    /** DeepL 翻译 API（专业翻译质量，对日/英/中等 30+ 语言对）。 */
    DEEPL
}

/** 常用目标语言预设（也允许 settings.targetLang 自由填）。 */
object TargetLangPresets {
    val ALL: List<Pair<String, String>> = listOf(
        "中文（简体）" to "zh-CN",
        "中文（繁体）" to "zh-TW",
        "English" to "en",
        "日本語" to "ja",
        "한국어" to "ko"
    )
}

/**
 * 百度 OCR 接口类型。每个 endpoint 在百度控制台有独立配额：
 * - `general_basic`：通用文字识别（标准版），每天 1000 次免费，无位置信息
 * - `general`：通用文字识别（标准含位置版），每天 500 次免费，**返回 boundingBox**
 * - `accurate_basic`：通用文字识别（高精度版），每天 500 次免费，识别更准但慢
 * - `accurate`：通用文字识别（高精度含位置版），每天 500 次免费，高精度 + 位置
 * - `webimage`：网络图片文字识别，500 次免费，适合复杂背景（如游戏画面）
 *
 * 含位置版（[hasLocation] = true）的响应里每条 word 带 `location` 字段；译文叠加层可以
 * 按该位置紧贴原文显示，体验远好于"无位置"版的"全部堆在屏幕中央"。
 */
@Serializable
enum class BaiduOcrEndpoint(
    val path: String,
    @StringRes val displayNameRes: Int,
    val hasLocation: Boolean,
    @StringRes val freeQuotaRes: Int
) {
    GENERAL_BASIC("general_basic", R.string.baidu_endpoint_general_basic_name, false, R.string.baidu_endpoint_quota_1000_day),
    GENERAL("general", R.string.baidu_endpoint_general_name, true, R.string.baidu_endpoint_quota_500_day),
    ACCURATE_BASIC("accurate_basic", R.string.baidu_endpoint_accurate_basic_name, false, R.string.baidu_endpoint_quota_500_day),
    ACCURATE("accurate", R.string.baidu_endpoint_accurate_name, true, R.string.baidu_endpoint_quota_500_day),
    WEBIMAGE("webimage", R.string.baidu_endpoint_webimage_name, false, R.string.baidu_endpoint_quota_500_day)
}

/**
 * 腾讯云 OCR Action 类型。所有接口都在 `ocr.tencentcloudapi.com`，只是 `X-TC-Action` header
 * 不同；响应都用 TextDetections 数组返回。三种各自独立配额：
 * - `GeneralBasicOCR`：通用印刷体识别，最常用，每月 1000 次免费
 * - `GeneralAccurateOCR`：高精度版，识别准确率明显更高但慢，每月 1000 次免费
 * - `RecognizeAgent`：智能 Agent 接口（LLM 增强），适合复杂版面 / 手写 / 表格混排
 */
@Serializable
enum class TencentOcrEndpoint(
    val action: String,
    @StringRes val displayNameRes: Int,
    @StringRes val descRes: Int
) {
    GENERAL_BASIC("GeneralBasicOCR", R.string.tencent_endpoint_general_basic_name, R.string.tencent_endpoint_general_basic_desc),
    GENERAL_ACCURATE("GeneralAccurateOCR", R.string.tencent_endpoint_general_accurate_name, R.string.tencent_endpoint_general_accurate_desc),
    RECOGNIZE_AGENT("RecognizeAgent", R.string.tencent_endpoint_recognize_agent_name, R.string.tencent_endpoint_recognize_agent_desc)
}

@Serializable
enum class OcrEngineKind {
    ML_KIT_AUTO,      // 自动选 latin / 日 / 中（M0 默认）
    ML_KIT_LATIN,
    ML_KIT_JAPANESE,
    ML_KIT_CHINESE,
    BAIDU,            // 百度通用文字识别（云端，需要 API Key + Secret）
    TENCENT,          // 腾讯云 GeneralBasicOCR（云端，需要 SecretId + SecretKey）
    PADDLE_ONNX       // PaddleOCR PP-OCRv5 mobile (ONNX Runtime 端侧，按需下载模型)
}

@Serializable
data class PreprocessOptions(
    val upscale2x: Boolean = false,
    val invert: Boolean = false,
    val binarize: Boolean = false
) {
    fun anyEnabled(): Boolean = upscale2x || invert || binarize
}

@Serializable
enum class RenderMode {
    /** 译文紧贴每段原文下方（按 OCR boundingBox）。 */
    BLOCKS,
    /** 屏幕底部整条横幅，列出所有原文 → 译文。 */
    BANNER
}

@Serializable
enum class OverlayPlacement {
    /** 紧贴原文下方，不遮挡原文（默认）。 */
    BELOW,
    /** 覆盖在原文上方，彻底替换显示。 */
    OVERLAP,
    /** 紧贴原文上方（适合下方有 UI 元素时）。 */
    ABOVE
}

@Serializable
enum class OverlayTheme {
    /** 经典深色：黑底白字。 */
    CLASSIC_DARK,
    /** 琥珀黑金：深棕底 + 暖金字（galgame 老派对话框感）。 */
    AMBER_GOLD,
    /** 浅色纸张：米色底 + 深褐字（漫画译文风）。 */
    PAPER_LIGHT,
    /** 半透明霜玻璃：蓝灰底 + 浅蓝字。 */
    FROST_GLASS,
    /** 自定义：bg/fg/border/border 粗细全由用户设置。 */
    CUSTOM
}
