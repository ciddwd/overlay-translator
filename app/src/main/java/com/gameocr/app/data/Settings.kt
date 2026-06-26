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
    val captureLoopIntervalMs: Long = 2000L,
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
    /**
     * 百度 OCR 识别语种。默认 CHN_ENG（中英）等于不指定时的行为。
     * 注意：含位置版（general / accurate / webimage）实际不读取 language_type；
     * 想识别韩文 / 日文等小语种应当切到「标准版」或「高精度版」（无位置）。
     */
    val baiduOcrLanguage: BaiduOcrLanguage = BaiduOcrLanguage.CHN_ENG,
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val tencentRegion: String = "ap-guangzhou",
    /** 腾讯云 OCR 接口类型。三种选择各自有独立配额、价格、识别能力。 */
    val tencentOcrEndpoint: TencentOcrEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
    /**
     * 腾讯云 OCR 识别语种。默认 auto 由后端按图片内容判断，多数场景体验最好。
     * GeneralAccurateOCR 只支持 auto / zh，RecognizeAgent 不读这个字段（引擎层会跳过）。
     */
    val tencentOcrLanguage: TencentOcrLanguage = TencentOcrLanguage.AUTO,
    val paddleModelMirrorUrl: String = "",
    val preferShizukuCapture: Boolean = false,
    val a11yVolumeTrigger: Boolean = false,
    val translatorEngine: TranslatorEngine = TranslatorEngine.OPENAI,
    val deeplApiKey: String = "",
    val deeplPro: Boolean = false,
    /**
     * DeepL 请求 / 响应协议。**与 [deeplBaseUrl] 解耦**，因为有的自架是 deeplx 协议、有的是
     * DeepL 官方兼容代理；URL 不应该决定协议。默认走 OFFICIAL 不破坏老配置。
     */
    val deeplProtocol: DeeplProtocol = DeeplProtocol.OFFICIAL,
    /**
     * DeepL 自定义 base URL（含末尾 `/`，例如 `http://localhost:1188/`）。
     * 空 = 按 [deeplPro] 选官方端点（free / pro）。自架 deeplx / Cloudflare worker 的用户填这里。
     * 非空时 [deeplPro] 失效（自定义后端不区分 free/pro），test connection 也改用 `translate` 探活。
     */
    val deeplBaseUrl: String = "",
    /**
     * 自定义 base URL 时的鉴权方式：false = `DeepL-Auth-Key <token>`（官方格式），true = `Bearer <token>`（部分 deeplx 部署）。
     * 仅在 [deeplBaseUrl] 非空时生效。鉴权用的 token 是 [deeplCustomToken]（**不是** [deeplApiKey]），避免把官方 key 误发给自架/第三方端点。
     */
    val deeplBearerAuth: Boolean = false,
    /**
     * 自定义 base URL 模式下专用的访问 token。与 [deeplApiKey]（官方 free/pro key）**完全隔离**，
     * 防止用户切换 URL 时把官方 key 泄漏给第三方。留空 = 不发 Authorization（裸 deeplx 无鉴权场景）。
     */
    val deeplCustomToken: String = "",
    /** 有道智云一套 AppKey/Secret，OCR (ocrapi) 与图片翻译 (ocrtransapi) 共用。 */
    val youdaoAppKey: String = "",
    val youdaoAppSecret: String = "",
    /** 悬浮按钮直径（dp）。 */
    val floatingButtonSizeDp: Int = 40,
    /**
     * 悬浮按钮 X 坐标（px，gravity=TOP|START 参考左上角）。-1 表示未保存过，按代码默认值
     * `(16dp, screenH/4)` 初始化。松手吸边后由 [FloatingButtonManager] 写回。
     */
    val floatingButtonX: Int = -1,
    val floatingButtonY: Int = -1,
    /** 松手是否自动吸附最近边（贴边时 1/3 藏出屏外 + 半透明待机）。关时松手停在原位。 */
    val floatingButtonSnapToEdge: Boolean = true,
    /**
     * 长按菜单关闭 / 操作完悬浮按钮后，若 3 秒未再次触摸则自动吸附最近边。
     * 仅在 [floatingButtonSnapToEdge] 也开启时生效。默认关，避免吓到老用户。
     */
    val floatingButtonAutoDock: Boolean = false,
    /**
     * 吸附时距实际屏幕物理边的内偏移（dp，0–40）。0 = 紧贴系统边；> 0 时让出 inset 宽度，
     * 用来避开全面屏左右边手势触发区。
     */
    val floatingButtonDockInsetDp: Int = 0,
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
     * 不再分裂成多个互相重叠的小框。默认关，按需在设置里开启。
     *
     * 阈值由 [mergeStrength] 选择：保守 / 标准 / 激进。
     */
    val mergeAdjacentBlocks: Boolean = false,
    /** 合并相邻 box 的强度档位，仅在 [mergeAdjacentBlocks] = true 时生效。 */
    val mergeStrength: MergeStrength = MergeStrength.STANDARD,
    /**
     * 用户在 LanguagePicker 里星标过的语言代码，按收藏顺序保存。
     * 列表里在最前，源语言 / 目标语言两个选择器共享同一份。
     */
    val pinnedLanguages: List<String> = emptyList(),
    /**
     * 明文 HTTP 白名单 host 列表（仅 hostname / IP，不含 scheme / port / path）。
     * 默认严格模式仅放行私有/回环地址；这里追加的 host 也允许明文访问，用于无 HTTPS 的可信外网服务。
     * **安全提示**：明文可被中间人窃听/篡改，仅在你确认链路可信时启用。
     */
    val cleartextAllowedHosts: List<String> = emptyList()
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

/**
 * OCR 合并相邻 box 的强度档位。从保守到激进——保守宁可让 OCR 输出散一些不误合，
 * 激进容忍更大间距 / 行高差，适合漫画气泡内多行被切碎的情形。
 */
@Serializable
enum class MergeStrength {
    /** 漫画 / 字幕短句：宽松阈值（gap 1.8x、垂直 1.3x、相交 15%），最容易合，可能误合相邻气泡。 */
    AGGRESSIVE,
    /** 默认：当前调优好的中间值（gap 1.2x、垂直 0.8x、相交 30%）。 */
    STANDARD,
    /** 视觉小说 / 长段密集场景：严格阈值（gap 0.8x、垂直 0.5x、相交 50%），少误合但段落易拆开。 */
    CONSERVATIVE
}

@Serializable
enum class DeeplProtocol {
    /**
     * DeepL 官方 v2/translate 协议：`Authorization: DeepL-Auth-Key`，body 是 form-urlencoded
     * (`text=...&target_lang=...`)，响应 `{translations:[{text,...}]}`。
     */
    OFFICIAL,
    /**
     * deeplx 协议（OwO-Network/DeepLX 及其常见 fork）：body 是 JSON
     * (`{text, source_lang, target_lang}`)，响应 `{code, data, ...}`，不支持 batch。
     */
    DEEPLX,
    /**
     * 混合：先用 deeplx 翻译，若 deeplx 失败 / 返回空，则用 DeepL 官方 key 补译。
     * 需要 deeplx Base URL（必填）+ DeepL 官方 API Key（用作 fallback）同时配置。
     */
    AUTO
}

@Serializable
enum class TranslatorEngine {
    /** OpenAI 兼容 LLM（DeepSeek / SiliconFlow / GPT / 自架 Ollama 等）。 */
    OPENAI,
    /** DeepL 翻译 API（专业翻译质量，对日/英/中等 30+ 语言对）。 */
    DEEPL,
    /**
     * 有道智云图片翻译（ocrtransapi）。**端到端引擎**：传整张截图，直接拿回带 box 的译文，
     * 无需先调 OCR 引擎。选中后 CaptureService 会跳过 [Settings.ocrEngine]。
     */
    YOUDAO_PICTRANS,
    /**
     * Google 翻译（非官方端点，无需 key）。谷歌可能随时限流 / 改端点 / 拒绝。国内需代理。
     */
    GOOGLE
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

/**
 * 百度 OCR 识别语种参数（`language_type` 字段值，REST API 大写形式）。
 *
 * 端点支持情况（官方文档 2024 实测）：
 * - **标准版 / 含位置版**（`general_basic` / `general`）：10 种主流语种（CHN_ENG / ENG / JAP / KOR / FRE / SPA / POR / GER / ITA / RUS），**不含 auto_detect**
 * - **高精度版 / 高精度含位置版**（`accurate_basic` / `accurate`）：全 25 种，含 `auto_detect`
 * - **网络图片**（`webimage`）：**不读 language_type**（永远走中英混合，文档没暴露该参数）
 *
 * UI 层根据当前 endpoint + 当前语种是否兼容，给出过滤 / 警告。运行时若用户选的语种在当前
 * endpoint 不支持，由 [BaiduOcrLanguage.coerceForEndpoint] 降级到 CHN_ENG 避免 216200 报错。
 */
@Serializable
enum class BaiduOcrLanguage(
    val code: String,
    /** BCP-47 主语言代码，null 表示该值不对应单一源语言（如 auto / 中英混合）。 */
    val bcp47: String?,
    @StringRes val displayNameRes: Int
) {
    AUTO_DETECT("auto_detect", null, R.string.ocr_lang_auto_detect),
    CHN_ENG("CHN_ENG", null, R.string.ocr_lang_chn_eng),
    ENG("ENG", "en", R.string.lang_en),
    JAP("JAP", "ja", R.string.lang_ja),
    KOR("KOR", "ko", R.string.lang_ko),
    FRE("FRE", "fr", R.string.lang_fr),
    SPA("SPA", "es", R.string.lang_es),
    POR("POR", "pt", R.string.lang_pt),
    GER("GER", "de", R.string.lang_de),
    ITA("ITA", "it", R.string.lang_it),
    RUS("RUS", "ru", R.string.lang_ru),
    DAN("DAN", "da", R.string.lang_da),
    DUT("DUT", "nl", R.string.lang_nl),
    MAL("MAL", "ms", R.string.lang_ms),
    SWE("SWE", "sv", R.string.lang_sv),
    IND("IND", "id", R.string.lang_id),
    POL("POL", "pl", R.string.lang_pl),
    ROM("ROM", "ro", R.string.lang_ro),
    TUR("TUR", "tr", R.string.lang_tr),
    GRE("GRE", "el", R.string.lang_el),
    HUN("HUN", "hu", R.string.lang_hu),
    THA("THA", "th", R.string.lang_th),
    VIE("VIE", "vi", R.string.lang_vi),
    ARA("ARA", "ar", R.string.lang_ar),
    HIN("HIN", "hi", R.string.lang_hi);

    /** 在 [endpoint] 上是否可用。 */
    fun supportedOn(endpoint: BaiduOcrEndpoint): Boolean = when (endpoint) {
        // 高精度系（含位置 + 无位置）支持全 25 种
        BaiduOcrEndpoint.ACCURATE_BASIC, BaiduOcrEndpoint.ACCURATE -> true
        // 标准系（含位置 + 无位置）只支持 10 种主流
        BaiduOcrEndpoint.GENERAL_BASIC, BaiduOcrEndpoint.GENERAL -> this in STANDARD_SUPPORTED
        // 网络图片端点不读 language_type
        BaiduOcrEndpoint.WEBIMAGE -> false
    }

    companion object {
        /** 标准系（general_basic / general）实际支持的子集（官方文档限定 10 种）。 */
        val STANDARD_SUPPORTED: Set<BaiduOcrLanguage> = setOf(
            CHN_ENG, ENG, JAP, KOR, FRE, SPA, POR, GER, ITA, RUS
        )

        /** 在 [endpoint] 不支持当前 [lang] 时返回该端点能用的最近替代值（用于运行时兜底）。 */
        fun coerceForEndpoint(lang: BaiduOcrLanguage, endpoint: BaiduOcrEndpoint): BaiduOcrLanguage {
            if (lang.supportedOn(endpoint)) return lang
            return CHN_ENG // 所有支持 language_type 的端点都支持 CHN_ENG
        }
    }
}

/**
 * 腾讯云 OCR 识别语种参数（`LanguageType` 字段）。
 *
 * 端点支持情况（官方文档 2024 实测）：
 * - **`GeneralBasicOCR`：支持全 23 种**（含 auto / mix / zh_rare 三个特殊值）
 * - **`GeneralAccurateOCR`：不接受 LanguageType**（多语种走 ConfigID="MulOCR"，本工程暂未接）
 * - **`RecognizeAgent`：不接受 LanguageType**（LLM 自动判断）
 *
 * 默认值 [AUTO]（"auto"）由后端按图片内容判断，覆盖多数场景。
 */
@Serializable
enum class TencentOcrLanguage(
    val code: String,
    /** BCP-47 主语言代码，null 表示该值不对应单一源语言。 */
    val bcp47: String?,
    @StringRes val displayNameRes: Int
) {
    AUTO("auto", null, R.string.ocr_lang_auto_detect),
    ZH("zh", null, R.string.ocr_lang_chn_eng),
    ZH_RARE("zh_rare", null, R.string.ocr_lang_zh_rare),
    MIX("mix", null, R.string.ocr_lang_mix),
    JA("jap", "ja", R.string.lang_ja),
    KO("kor", "ko", R.string.lang_ko),
    SPA("spa", "es", R.string.lang_es),
    FRE("fre", "fr", R.string.lang_fr),
    GER("ger", "de", R.string.lang_de),
    POR("por", "pt", R.string.lang_pt),
    VIE("vie", "vi", R.string.lang_vi),
    MAY("may", "ms", R.string.lang_ms),
    RUS("rus", "ru", R.string.lang_ru),
    ITA("ita", "it", R.string.lang_it),
    HOL("hol", "nl", R.string.lang_nl),
    SWE("swe", "sv", R.string.lang_sv),
    FIN("fin", "fi", R.string.lang_fi),
    DAN("dan", "da", R.string.lang_da),
    NOR("nor", "nb", R.string.lang_nb),
    HUN("hun", "hu", R.string.lang_hu),
    THA("tha", "th", R.string.lang_th),
    HIN("hi", "hi", R.string.lang_hi),
    ARA("ara", "ar", R.string.lang_ar);

    /** 在 [endpoint] 上是否可用。 */
    fun supportedOn(endpoint: TencentOcrEndpoint): Boolean = when (endpoint) {
        TencentOcrEndpoint.GENERAL_BASIC -> true
        TencentOcrEndpoint.GENERAL_ACCURATE, TencentOcrEndpoint.RECOGNIZE_AGENT -> false
    }
}

@Serializable
enum class OcrEngineKind {
    ML_KIT_AUTO,      // 自动选 latin / 日 / 韩 / 中（按文字类型探测）
    ML_KIT_LATIN,
    ML_KIT_JAPANESE,
    ML_KIT_CHINESE,
    ML_KIT_KOREAN,    // ML Kit 韩文识别器（端侧、~20MB 模型按需下载）
    BAIDU,            // 百度通用文字识别（云端，需要 API Key + Secret）
    TENCENT,          // 腾讯云 GeneralBasicOCR（云端，需要 SecretId + SecretKey）
    YOUDAO,           // 有道智云通用文字识别 ocrapi（云端，需要 AppKey + AppSecret）
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
