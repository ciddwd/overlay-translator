package com.gameocr.app.data

import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureRegion
import kotlinx.serialization.Serializable
import java.security.MessageDigest

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
    /**
     * 保存 [captureRegion] 时的屏幕物理尺寸（px）。用于读取 region 时按当前屏幕尺寸自动 rescale，
     * 避免用户竖屏框完一区域，旋转横屏后 region 坐标错位。0 = 历史数据（没记录），跳过 rescale。
     */
    val captureRegionSavedScreenW: Int = 0,
    val captureRegionSavedScreenH: Int = 0,
    val overlayTextSizeSp: Int = 14,
    val overlayAlpha: Float = 0.85f,
    val overlayFontFileName: String = "",
    val overlayFontDisplayName: String = "",
    val overlayFonts: List<OverlayFontEntry> = emptyList(),
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
    /**
     * OCR 调用前 / 后自动判别文本方向（横排 / 竖排 / 一字母一行的 logo），按判别结果在路由层
     * 动态切换 OCR 引擎（比如发现是日漫竖排自动切到 manga-ocr，发现是繁中竖排自动切到百度
     * 含位置版）。方向模型已随 APK 打包，默认开启以覆盖竖排 / 旋转屏译场景。
     *
     * Phase 1 实现为"OCR 后判别 → 不匹配则用更合适的引擎重跑"——横排场景零额外开销，竖排
     * 误用其它引擎时 OCR 跑 2 次。详见 [com.gameocr.app.ocr.OrientationCoordinator]。
     */
    val textOrientationAutoDetect: Boolean = true,
    /**
     * 用户手动锁定文本方向，覆盖自动判别。null = 走自动 / 关闭时无意义。
     * 通常仅在自动判别频繁误判某帧时由用户临时锁定。
     */
    val manualTextOrientation: com.gameocr.app.ocr.TextOrientation? = null,
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
    /** Umi-OCR HTTP image OCR endpoint, e.g. http://192.168.0.2:1224/api/ocr. */
    val umiOcrBaseUrl: String = "",
    /** LunaTranslator HTTP image OCR endpoint, e.g. http://192.168.0.2:2333/api/ocr. */
    val lunaOcrBaseUrl: String = "",
    val paddleAiStudioToken: String = "",
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
    val paddleModelVersion: PaddleModelVersion = PaddleModelVersion.V6_SMALL,
    val paddleModelMirrorUrl: String = "",
    /**
     * manga-ocr 模型下载镜像 URL（可选）。l0wgear/manga-ocr-2025-onnx 没有公开 hf-mirror 代理
     * （实测 308 redirect 回 huggingface.co），用户可填自架镜像（如内网 NAS）。空 = 仅走 huggingface.co 原站。
     */
    val mangaOcrModelMirrorUrl: String = "",
    /** PaddleOCR doc-orientation ONNX model mirror. Empty = official HuggingFace source. */
    val orientationModelMirrorUrl: String = "",
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
    /** 火山引擎机器翻译 AccessKey ID（SignV4 鉴权用）。 */
    val volcAccessKeyId: String = "",
    val volcSecretAccessKey: String = "",
    /** 火山引擎区域，国内默认 cn-north-1（目前火山翻译只开放这一个区域）。 */
    val volcRegion: String = "cn-north-1",
    /** 百度翻译开放平台 APPID（fanyi-api.baidu.com，**不是**百度智能云 OCR 那套）。 */
    val baiduFanyiAppId: String = "",
    /** 百度翻译开放平台密钥，签名用 md5(appid+q+salt+key)。 */
    val baiduFanyiSecretKey: String = "",
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
    /**
     * 悬浮窗口（[RenderMode.FLOATING_WINDOW]）位置 / 大小。-1 表示首次未保存过 → 居中并使用默认尺寸。
     * 拖动 / 缩放后由 [overlay.DraggableOverlayWindow] 写回。
     */
    val floatingWindowX: Int = -1,
    val floatingWindowY: Int = -1,
    val floatingWindowWidthDp: Int = 320,
    val floatingWindowHeightDp: Int = 180,
    /** 悬浮窗口内容形态：原文+译文 / 仅译文。 */
    val floatingWindowContentMode: FloatingWindowContentMode = FloatingWindowContentMode.SRC_AND_DST,
    /** 锁定悬浮窗口位置/大小：true 时不可拖拽 / 不可缩放（避免游戏中误触）。 */
    val floatingWindowLocked: Boolean = false,
    /**
     * 自定义主题的边框样式（仿 CSS border-style）。仅在 [overlayTheme] = CUSTOM 时生效，
     * 对 BLOCKS 模式 box + FLOATING_WINDOW 模式的悬浮窗都生效。0.3.x 字段名 floatingWindowBorderStyle
     * 已被 silent-migrate 到这里。
     */
    val customBorderStyle: BorderStyle = BorderStyle.SOLID,
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
    val cleartextAllowedHosts: List<String> = emptyList(),
    /**
     * 悬浮球长按弧菜单按钮顺序。每页按钮数由 [arcMenuPageSize] 决定，范围为
     * [FloatingMenu.MIN_PAGE_SIZE]..[FloatingMenu.MAX_PAGE_SIZE]；超出时由 FloatingButtonManager
     * 自动在每页末位插入「下一组」翻页项，最后一页循环回第一页。新装用户 / 未自定义的旧默认顺序迁移到
     * [FloatingMenu.DEFAULT_ORDER]。
     *
     * `FULL_SCREEN_SKILL` / `WORD_SELECT` 在 registry 里互斥——展开菜单时只显示「与当前
     * [floatingButtonSkill] 相反」的那一个，点击切 skill。所以 order 里只用一个 slot 表示
     * 「技能切换槽」，约定写 `FULL_SCREEN_SKILL`。
     */
    val floatingMenuItemOrder: List<MenuItemId> = FloatingMenu.DEFAULT_ORDER,
    val arcMenuPageSize: Int = FloatingMenu.DEFAULT_PAGE_SIZE,
    /**
     * 主球单击触发的「技能」。FULL_SCREEN 走全屏 OCR+翻译；WORD_SELECT 进入划词框选 overlay。
     * 长按菜单里的「技能槽」按钮显示当前对立项，点了切换 + 球图标互换。
     */
    val floatingButtonSkill: FloatingSkill = FloatingSkill.FULL_SCREEN,
    /**
     * 划词翻译：单词模式专用的 LLM 词典 prompt 模板（仅 OpenAI 兼容引擎生效）。
     * 用占位符 `{source}` / `{target}` 同 [promptTemplate]。返回 JSON 让卡片显示音标 / 词性 /
     * 释义 / 例句；解析失败回退到 [promptTemplate]。读取时若 key 缺省，按 UI locale 给出本地化默认。
     */
    val dictionaryPrompt: String = DEFAULT_DICTIONARY_PROMPT,
    /**
     * 端侧 LLM 采样温度。Hy-MT / Sakura 可共用；当前 binding 尚未把采样参数暴露到 JNI。
     */
    val localLlmTemperature: Float = 0.7f,
    /** 端侧 LLM nucleus 采样 top-p。 */
    val localLlmTopP: Float = 0.6f,
    /** 端侧 LLM top-k。 */
    val localLlmTopK: Int = 20,
    /** 端侧 LLM 重复惩罚。 */
    val localLlmRepetitionPenalty: Float = 1.05f,
    /** 端侧 LLM 上下文窗口大小（token）。屏译 OCR 段落短，2048 足够；越大越占内存。 */
    val localLlmContextSize: Int = 2048,
    /** 端侧 LLM 单次最长生成 token 数。屏译场景译文很少超过 256 token。 */
    val localLlmMaxNewTokens: Int = 256,
    /**
     * PaddleOCR / MangaOCR 共用 DBNet 检测的二值化阈值。prob map > 此值视为前景。
     * 主线 PaddleOCR 默认 0.3；屏译降到 0.25 让漫画小气泡、淡色字、长竖排能稳定捕获。
     * 用户可在设置→OCR→"检测高级阈值"调到 0.15–0.4，过低引入噪声 box / 过高漏小字。
     */
    val dbnetProbThresh: Float = 0.25f,
    /**
     * DBNet 连通域平均概率阈值。连通域内像素的 prob 均值低于此值视为噪声丢弃。
     * 主线默认 0.6；屏译降到 0.5 配合 [dbnetProbThresh] 一起放宽，捕获概率响应在边界的小字。
     */
    val dbnetBoxScoreThresh: Float = 0.5f,
    /**
     * PaddleOCR DBNet 旋转矩形外扩比例。从二值连通域到最终 box 的 unclip 操作，
     * 越大 box 包得越宽。普通 PaddleOCR 保持 1.55，避免为了日漫 crop 需求改变通用 OCR 行为。
     */
    val dbnetUnclipRatio: Float = 1.55f,
    /**
     * manga-ocr 专用 DBNet 外扩比例。manga-ocr 识别整气泡 crop，竖排/手绘字体更怕首尾字被裁；
     * 1.65 比 PaddleOCR 常见 1.5 默认值多一点裁剪余量，同时仍避免过度吞邻泡。
     */
    val mangaOcrDbnetUnclipRatio: Float = 1.65f,
    /**
     * BubbleClusterer 聚类时把每个 DBNet quad 外扩多少像素后做并查集合并。
     * 18 px 是 PaddleOCR DBNet 论文常见列距下限；屏译漫画竖排两列字间距经常 20–40 px，
     * 升到 32 px 让「キャプテン / お疲れ様でした!!」这类两列气泡能聚为单个 bubble 整体送入
     * manga-ocr。过大可能误合相邻独立气泡，用户可在设置滑条调 8–60。
     */
    val bubbleClusterGap: Int = 32,
    /**
     * 端侧 LLM 下载源选择。默认 [LlmMirrorChoice.HF_MIRROR]——国内用户绝大多数直连可达：
     * - Hy-MT2 / Sakura 在此模式下走 hf-mirror.com。
     * 选 [LlmMirrorChoice.CUSTOM] 时使用 [localLlmMirrorUrl] 作 base URL。
     */
    val localLlmMirror: LlmMirrorChoice = LlmMirrorChoice.HF_MIRROR,
    /**
     * [LlmMirrorChoice.CUSTOM] 模式下的自定义 base URL（含末尾 `/`，例如
     * `https://my-cdn.example/llm/`）。拼接规则为 `<base>/<kind.fileName>`。其它模式不读此字段。
     */
    val localLlmMirrorUrl: String = "",
    val translationPresets: List<TranslationPreset> = emptyList(),
    val activeTranslationPresetId: String = ""
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

        /**
         * 划词翻译的词典模式默认 prompt。要求 LLM 在输入是单词时返回严格 JSON——
         * 解析失败由 CaptureService 回退到纯翻译，不报错；解析成功则把 phonetic / pos /
         * definitions / examples 显示在卡片字典区。
         */
        const val DEFAULT_DICTIONARY_PROMPT: String = """你是一名{source}→{target}的双语词典助手。请把用户输入当作一个单词或固定短语来处理，**只输出**下面格式的 JSON，不要加 markdown、代码块、解释。
{
  "phonetic": "音标或读音（{source}; 无则空串）",
  "pos": ["词性，{target}缩写，如 名/动/形 或 n./v./adj.; 无则空数组"],
  "definitions": ["{target}释义 1", "{target}释义 2"],
  "examples": [
    { "src": "{source}例句", "dst": "{target}译文" }
  ]
}
要求：
1. 必须是合法 JSON，键名与上面完全一致；
2. 没有信息的字段用空串或空数组占位；
3. 例句最多 2 条，太多删减；
4. 不要把整段当句子翻译，只做词典查询。
"""
    }
}

@Serializable
data class OverlayFontEntry(
    val fileName: String,
    val displayName: String
)

@Serializable
data class TranslationPreset(
    val id: String,
    val name: String,
    val shortName: String = name.take(8),
    val baseUrl: String = "https://api.deepseek.com/v1/",
    val model: String = "deepseek-v4-flash",
    val sourceLang: String = Languages.AUTO.code,
    val targetLang: String = "zh-CN",
    val promptTemplate: String = Settings.DEFAULT_PROMPT,
    val dictionaryPrompt: String = Settings.DEFAULT_DICTIONARY_PROMPT,
    val ocrEngine: OcrEngineKind = OcrEngineKind.ML_KIT_AUTO,
    val preprocess: PreprocessOptions = PreprocessOptions(),
    val renderMode: RenderMode = RenderMode.BLOCKS,
    val overlayPlacement: OverlayPlacement = OverlayPlacement.OVERLAP,
    val overlayTheme: OverlayTheme = OverlayTheme.CLASSIC_DARK,
    val customBgColor: Int = 0xE6000000.toInt(),
    val customFgColor: Int = 0xFFFFFFFF.toInt(),
    val customBorderColor: Int = 0x00000000,
    val customBorderWidth: Int = 0,
    val customBorderStyle: BorderStyle = BorderStyle.SOLID,
    val overlayTextSizeSp: Int = 14,
    val overlayAlpha: Float = 0.85f,
    val overlayFontFileName: String = "",
    val overlayFontDisplayName: String = "",
    val overlayOffsetX: Int = 0,
    val overlayOffsetY: Int = 0,
    val overlayAllowWrap: Boolean = true,
    val overlayAvoidCollision: Boolean = true,
    val streamingTranslate: Boolean = true,
    val translatorEngine: TranslatorEngine = TranslatorEngine.OPENAI,
    val deeplPro: Boolean = false,
    val deeplProtocol: DeeplProtocol = DeeplProtocol.OFFICIAL,
    val deeplBaseUrl: String = "",
    val deeplBearerAuth: Boolean = false,
    val baiduOcrEndpoint: BaiduOcrEndpoint = BaiduOcrEndpoint.GENERAL,
    val baiduOcrLanguage: BaiduOcrLanguage = BaiduOcrLanguage.CHN_ENG,
    val umiOcrBaseUrl: String = "",
    val lunaOcrBaseUrl: String = "",
    val tencentRegion: String = "ap-guangzhou",
    val tencentOcrEndpoint: TencentOcrEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
    val tencentOcrLanguage: TencentOcrLanguage = TencentOcrLanguage.AUTO,
    val paddleModelVersion: PaddleModelVersion = PaddleModelVersion.V6_SMALL,
    val apiTimeoutSeconds: Int = 30,
    val mergeAdjacentBlocks: Boolean = false,
    val mergeStrength: MergeStrength = MergeStrength.STANDARD,
    val textOrientationAutoDetect: Boolean = true,
    val manualTextOrientation: com.gameocr.app.ocr.TextOrientation? = null,
    val localLlmTemperature: Float = 0.7f,
    val localLlmTopP: Float = 0.6f,
    val localLlmTopK: Int = 20,
    val localLlmRepetitionPenalty: Float = 1.05f,
    val localLlmContextSize: Int = 2048,
    val localLlmMaxNewTokens: Int = 256,
    val dbnetProbThresh: Float = 0.25f,
    val dbnetBoxScoreThresh: Float = 0.5f,
    val dbnetUnclipRatio: Float = 1.55f,
    val mangaOcrDbnetUnclipRatio: Float = 1.65f,
    val bubbleClusterGap: Int = 32,
    val settingsHash: String = ""
) {
    fun applyTo(settings: Settings): Settings = settings.copy(
        baseUrl = baseUrl,
        model = model,
        sourceLang = sourceLang,
        targetLang = targetLang,
        promptTemplate = promptTemplate,
        dictionaryPrompt = dictionaryPrompt,
        ocrEngine = ocrEngine,
        preprocess = preprocess,
        renderMode = renderMode,
        overlayPlacement = overlayPlacement,
        overlayTheme = overlayTheme,
        customBgColor = customBgColor,
        customFgColor = customFgColor,
        customBorderColor = customBorderColor,
        customBorderWidth = customBorderWidth,
        customBorderStyle = customBorderStyle,
        overlayTextSizeSp = overlayTextSizeSp,
        overlayAlpha = overlayAlpha,
        overlayFontFileName = overlayFontFileName,
        overlayFontDisplayName = overlayFontDisplayName,
        overlayOffsetX = overlayOffsetX,
        overlayOffsetY = overlayOffsetY,
        overlayAllowWrap = overlayAllowWrap,
        overlayAvoidCollision = overlayAvoidCollision,
        streamingTranslate = streamingTranslate,
        translatorEngine = translatorEngine,
        deeplPro = deeplPro,
        deeplProtocol = deeplProtocol,
        deeplBaseUrl = deeplBaseUrl,
        deeplBearerAuth = deeplBearerAuth,
        baiduOcrEndpoint = baiduOcrEndpoint,
        baiduOcrLanguage = baiduOcrLanguage,
        umiOcrBaseUrl = umiOcrBaseUrl,
        lunaOcrBaseUrl = lunaOcrBaseUrl,
        tencentRegion = tencentRegion,
        tencentOcrEndpoint = tencentOcrEndpoint,
        tencentOcrLanguage = tencentOcrLanguage,
        paddleModelVersion = paddleModelVersion,
        apiTimeoutSeconds = apiTimeoutSeconds,
        mergeAdjacentBlocks = mergeAdjacentBlocks,
        mergeStrength = mergeStrength,
        textOrientationAutoDetect = textOrientationAutoDetect,
        manualTextOrientation = manualTextOrientation,
        localLlmTemperature = localLlmTemperature,
        localLlmTopP = localLlmTopP,
        localLlmTopK = localLlmTopK,
        localLlmRepetitionPenalty = localLlmRepetitionPenalty,
        localLlmContextSize = localLlmContextSize,
        localLlmMaxNewTokens = localLlmMaxNewTokens,
        dbnetProbThresh = dbnetProbThresh,
        dbnetBoxScoreThresh = dbnetBoxScoreThresh,
        dbnetUnclipRatio = dbnetUnclipRatio,
        mangaOcrDbnetUnclipRatio = mangaOcrDbnetUnclipRatio,
        bubbleClusterGap = bubbleClusterGap
    )
}

object TranslationPresetCatalog {
    const val BUILTIN_MANGA_JA_ZH: String = "builtin_manga_ja_zh"
    const val UNSAVED_DRAFT_ID: String = "custom_unsaved_translation_preset"

    fun builtIns(): List<TranslationPreset> = listOf(
        fromSettings(
            id = BUILTIN_MANGA_JA_ZH,
            name = "Offline Manga OCR to Chinese",
            shortName = "Manga",
            settings = Settings().copy(
                sourceLang = "ja",
                targetLang = "zh-CN",
                ocrEngine = OcrEngineKind.MANGA_OCR_JA,
                translatorEngine = TranslatorEngine.LOCAL_SAKURA,
                overlayTheme = OverlayTheme.PAPER_LIGHT,
                mergeAdjacentBlocks = true,
                mergeStrength = MergeStrength.AGGRESSIVE
            )
        )
    )

    fun all(custom: List<TranslationPreset>): List<TranslationPreset> =
        builtIns() + custom.filterNot { it.id in builtInIds }

    fun find(custom: List<TranslationPreset>, id: String): TranslationPreset? =
        all(custom).firstOrNull { it.id == id }

    fun fromSettings(
        id: String,
        name: String,
        shortName: String,
        settings: Settings
    ): TranslationPreset {
        val preset = TranslationPreset(
            id = id,
            name = name,
            shortName = shortName,
            baseUrl = settings.baseUrl,
            model = settings.model,
            sourceLang = settings.sourceLang,
            targetLang = settings.targetLang,
            promptTemplate = settings.promptTemplate,
            dictionaryPrompt = settings.dictionaryPrompt,
            ocrEngine = settings.ocrEngine,
            preprocess = settings.preprocess,
            renderMode = settings.renderMode,
            overlayPlacement = settings.overlayPlacement,
            overlayTheme = settings.overlayTheme,
            customBgColor = settings.customBgColor,
            customFgColor = settings.customFgColor,
            customBorderColor = settings.customBorderColor,
            customBorderWidth = settings.customBorderWidth,
            customBorderStyle = settings.customBorderStyle,
            overlayTextSizeSp = settings.overlayTextSizeSp,
            overlayAlpha = settings.overlayAlpha,
            overlayFontFileName = settings.overlayFontFileName,
            overlayFontDisplayName = settings.overlayFontDisplayName,
            overlayOffsetX = settings.overlayOffsetX,
            overlayOffsetY = settings.overlayOffsetY,
            overlayAllowWrap = settings.overlayAllowWrap,
            overlayAvoidCollision = settings.overlayAvoidCollision,
            streamingTranslate = settings.streamingTranslate,
            translatorEngine = settings.translatorEngine,
            deeplPro = settings.deeplPro,
            deeplProtocol = settings.deeplProtocol,
            deeplBaseUrl = settings.deeplBaseUrl,
            deeplBearerAuth = settings.deeplBearerAuth,
            baiduOcrEndpoint = settings.baiduOcrEndpoint,
            baiduOcrLanguage = settings.baiduOcrLanguage,
            umiOcrBaseUrl = settings.umiOcrBaseUrl,
            lunaOcrBaseUrl = settings.lunaOcrBaseUrl,
            tencentRegion = settings.tencentRegion,
            tencentOcrEndpoint = settings.tencentOcrEndpoint,
            tencentOcrLanguage = settings.tencentOcrLanguage,
            paddleModelVersion = settings.paddleModelVersion,
            apiTimeoutSeconds = settings.apiTimeoutSeconds,
            mergeAdjacentBlocks = settings.mergeAdjacentBlocks,
            mergeStrength = settings.mergeStrength,
            textOrientationAutoDetect = settings.textOrientationAutoDetect,
            manualTextOrientation = settings.manualTextOrientation,
            localLlmTemperature = settings.localLlmTemperature,
            localLlmTopP = settings.localLlmTopP,
            localLlmTopK = settings.localLlmTopK,
            localLlmRepetitionPenalty = settings.localLlmRepetitionPenalty,
            localLlmContextSize = settings.localLlmContextSize,
            localLlmMaxNewTokens = settings.localLlmMaxNewTokens,
            dbnetProbThresh = settings.dbnetProbThresh,
            dbnetBoxScoreThresh = settings.dbnetBoxScoreThresh,
            dbnetUnclipRatio = settings.dbnetUnclipRatio,
            mangaOcrDbnetUnclipRatio = settings.mangaOcrDbnetUnclipRatio,
            bubbleClusterGap = settings.bubbleClusterGap
        )
        return preset.copy(settingsHash = settingsHash(preset))
    }

    fun matchesSettings(preset: TranslationPreset, settings: Settings): Boolean {
        return matchesHash(preset, hashForSettings(settings))
    }

    fun hashForSettings(settings: Settings): String = fromSettings(
        id = UNSAVED_DRAFT_ID,
        name = "",
        shortName = "",
        settings = settings
    ).settingsHash

    fun matchesHash(preset: TranslationPreset, settingsHash: String): Boolean =
        preset.settingsHash.ifBlank { settingsHash(preset) } == settingsHash

    private fun settingsHash(preset: TranslationPreset): String = sha256(
        preset.baseUrl,
        preset.model,
        preset.sourceLang,
        preset.targetLang,
        preset.promptTemplate,
        preset.dictionaryPrompt,
        preset.ocrEngine.name,
        preset.preprocess.upscale2x,
        preset.preprocess.invert,
        preset.preprocess.binarize,
        preset.renderMode.name,
        preset.overlayPlacement.name,
        preset.overlayTheme.name,
        preset.customBgColor,
        preset.customFgColor,
        preset.customBorderColor,
        preset.customBorderWidth,
        preset.customBorderStyle.name,
        preset.overlayTextSizeSp,
        preset.overlayAlpha.toBits(),
        preset.overlayFontFileName,
        preset.overlayFontDisplayName,
        preset.overlayOffsetX,
        preset.overlayOffsetY,
        preset.overlayAllowWrap,
        preset.overlayAvoidCollision,
        preset.streamingTranslate,
        preset.translatorEngine.name,
        preset.deeplPro,
        preset.deeplProtocol.name,
        preset.deeplBaseUrl,
        preset.deeplBearerAuth,
        preset.baiduOcrEndpoint.name,
        preset.baiduOcrLanguage.name,
        preset.umiOcrBaseUrl,
        preset.lunaOcrBaseUrl,
        preset.tencentRegion,
        preset.tencentOcrEndpoint.name,
        preset.tencentOcrLanguage.name,
        preset.paddleModelVersion.name,
        preset.apiTimeoutSeconds,
        preset.mergeAdjacentBlocks,
        preset.mergeStrength.name,
        preset.textOrientationAutoDetect,
        preset.manualTextOrientation?.name.orEmpty(),
        preset.localLlmTemperature.toBits(),
        preset.localLlmTopP.toBits(),
        preset.localLlmTopK,
        preset.localLlmRepetitionPenalty.toBits(),
        preset.localLlmContextSize,
        preset.localLlmMaxNewTokens,
        preset.dbnetProbThresh.toBits(),
        preset.dbnetBoxScoreThresh.toBits(),
        preset.dbnetUnclipRatio.toBits(),
        preset.mangaOcrDbnetUnclipRatio.toBits(),
        preset.bubbleClusterGap
    )

    private fun sha256(vararg parts: Any?): String {
        val source = buildString {
            parts.forEach { part ->
                val value = part?.toString().orEmpty()
                append(value.length)
                append(':')
                append(value)
                append('|')
            }
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0x0f])
            }
        }
    }

    fun upsertCustom(
        custom: List<TranslationPreset>,
        preset: TranslationPreset
    ): List<TranslationPreset> {
        val cleaned = custom.filterNot { it.id == preset.id || it.id in builtInIds }
        return if (preset.id in builtInIds) cleaned else cleaned + preset
    }

    fun isBuiltIn(id: String): Boolean = id in builtInIds

    private val builtInIds: Set<String> = setOf(BUILTIN_MANGA_JA_ZH)
}

/**
 * 主球单击技能。FULL_SCREEN 走 CaptureService.triggerOnce()（全屏 OCR+翻译）；
 * WORD_SELECT 走 CaptureService.triggerWordSelect()（拖矩形 → 单段翻译卡片）。
 */
@Serializable
enum class FloatingSkill {
    FULL_SCREEN,
    WORD_SELECT
}

/**
 * 悬浮球弧菜单按钮 ID。在 `overlay/MenuItemRegistry.kt` 集中绑定到图标 / 文案 / 回调。
 *
 * `FULL_SCREEN_SKILL` 是「技能槽」占位 ID：在 menu 里它代表「与当前 [FloatingSkill] 相反」
 * 的那一项 —— 当前 skill = FULL_SCREEN 时显示 WORD_SELECT 入口图标，反之亦然。
 * 这样 [Settings.floatingMenuItemOrder] 只需要一个 slot，不会产生「同时显示两个互斥按钮」的奇怪状态。
 */
@Serializable
enum class MenuItemId {
    LOOP,
    REGION,
    LANGUAGE_PAIR,
    PRESET_SWITCH,
    SETTINGS,
    HOME,
    FULL_SCREEN_SKILL
}

/** 弧菜单分页 / 默认顺序常量。 */
object FloatingMenu {
    /** 每页按钮数范围；该数量包含「下一组」翻页键。 */
    const val MIN_PAGE_SIZE: Int = 2
    const val MAX_PAGE_SIZE: Int = 6
    const val DEFAULT_PAGE_SIZE: Int = 5
    /** 旧调用点兼容别名，等同 [DEFAULT_PAGE_SIZE]。 */
    const val PAGE_SIZE: Int = DEFAULT_PAGE_SIZE

    fun coercePageSize(value: Int): Int = value.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)

    /**
     * 全部已知 MenuItemId 的稳定顺序（用于读 Settings 时补齐遗漏 + 默认值）。
     * 任何不在此列表的 id（未来新增引脚 / 引入第三方 plugin）都视为未知 silently 跳过。
     */
    val ALL_ORDER: List<MenuItemId> = listOf(
        MenuItemId.LOOP,
        MenuItemId.REGION,
        MenuItemId.LANGUAGE_PAIR,
        MenuItemId.PRESET_SWITCH,
        MenuItemId.SETTINGS,
        MenuItemId.HOME,
        MenuItemId.FULL_SCREEN_SKILL
    )

    /**
     * 首次安装默认顺序：循环、选区、划词技能槽、返回主应用。
     * 把「技能槽」放在「返回主应用」之前——拖球到菜单时手指先经过的位置留给「常用动作」，
     * HOME 作为"离场"动作放最后符合直觉。
     */
    val DEFAULT_ORDER: List<MenuItemId> = listOf(
        MenuItemId.LOOP,
        MenuItemId.REGION,
        MenuItemId.FULL_SCREEN_SKILL,
        MenuItemId.LANGUAGE_PAIR,
        MenuItemId.PRESET_SWITCH,
        MenuItemId.SETTINGS,
        MenuItemId.HOME
    )

    val LEGACY_DEFAULT_ORDER_BEFORE_PRESET_LANGUAGE_SWAP: List<MenuItemId> = listOf(
        MenuItemId.LOOP,
        MenuItemId.REGION,
        MenuItemId.FULL_SCREEN_SKILL,
        MenuItemId.PRESET_SWITCH,
        MenuItemId.LANGUAGE_PAIR,
        MenuItemId.SETTINGS,
        MenuItemId.HOME
    )

    val LEGACY_DEFAULT_ORDER_BEFORE_PRESET_SKILL_SWAP: List<MenuItemId> = listOf(
        MenuItemId.LOOP,
        MenuItemId.REGION,
        MenuItemId.PRESET_SWITCH,
        MenuItemId.FULL_SCREEN_SKILL,
        MenuItemId.LANGUAGE_PAIR,
        MenuItemId.SETTINGS,
        MenuItemId.HOME
    )

    val LEGACY_DEFAULT_ORDER_BEFORE_SKILL_SWAP: List<MenuItemId> = listOf(
        MenuItemId.LOOP,
        MenuItemId.REGION,
        MenuItemId.PRESET_SWITCH,
        MenuItemId.SETTINGS,
        MenuItemId.LANGUAGE_PAIR,
        MenuItemId.FULL_SCREEN_SKILL,
        MenuItemId.HOME
    )
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

/**
 * PaddleOCR 模型版本。支持多版本切换：v5 mobile（当前默认）和 v6 tiny（更轻量、更快）。
 * 每个版本的模型文件存在独立子目录（[PaddleModelInstaller.modelsDir] / [dirName]），
 * 切版本时不需要删另一个版本的模型。
 *
 * 枚举值的 name 会被 DataStore 序列化，改名需做 silent migration。
 */
@Serializable
enum class PaddleModelVersion(
    val displayNameRes: Int,
    val descRes: Int,
    /** modelsDir 下的子目录名 */
    val dirName: String
) {
    /** PP-OCRv5 mobile（det 4.5MB + rec 15.7MB + dict 90KB，~20MB 总计） */
    V5_MOBILE(
        R.string.paddle_version_v5_mobile,
        R.string.paddle_version_v5_mobile_desc,
        "v5"
    ),
    /** PP-OCRv6 tiny（det ~1.5M params + rec ~1.5M params，极轻量极快） */
    V6_TINY(
        R.string.paddle_version_v6_tiny,
        R.string.paddle_version_v6_tiny_desc,
        "v6tiny"
    ),
    /** PP-OCRv6 small: mobile tier with higher accuracy than tiny at a higher runtime cost. */
    V6_SMALL(
        R.string.paddle_version_v6_small,
        R.string.paddle_version_v6_small_desc,
        "v6small"
    ),
    /** PP-OCRv6 medium: higher accuracy tier with a larger download/runtime cost. */
    V6_MEDIUM(
        R.string.paddle_version_v6_medium,
        R.string.paddle_version_v6_medium_desc,
        "v6medium"
    );
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
    GOOGLE,
    /**
     * 火山引擎机器翻译（open.volcengineapi.com）。原生支持 TextList 批量；走 Volcengine SignV4
     * 鉴权（service=translate / region=cn-north-1）。需要在火山控制台开通"机器翻译"并拿 AK/SK。
     */
    VOLC,
    /**
     * 百度翻译开放平台（fanyi-api.baidu.com）。**与 [Settings.baiduOcrApiKey] 完全不是同一个产品**
     * （那是百度智能云 OCR）。签名简单：md5(appid+q+salt+key)；个人免费档 1QPS / 5万字符/月。
     */
    BAIDU_FANYI,
    /**
     * 腾讯云翻译 TMT（tmt.tencentcloudapi.com）。**复用 [Settings.tencentSecretId] /
     * [Settings.tencentSecretKey] / [Settings.tencentRegion]** 同一套腾讯云子账号——
     * 因为属于同一个腾讯云账号体系，让用户填两遍只会困惑。
     */
    TENCENT,
    /**
     * 端侧 LLM 翻译 —— SakuraLLM Qwen2.5-1.5B Q5KS（约 1.26 GB），日译中 ACGN 专用。
     * 走 [com.gameocr.app.llm.LlamaEngineHolder] + llama.cpp（com.arm.aichat binding）。
     * 仅 Android 13+ 可用（binding minSdk=33）。模型按需下载，5 分钟空闲自动 unload。
     * 选中后强制目标语种为简体中文；源语种非日文时 RoutingTranslator 回退到 OpenAI 兼容引擎。
     *
     * 历史：曾同时支持 LOCAL_HUNYUAN_MT（HY-MT1.5 1.25bit/2bit GGUF），但腾讯 AngelSlim 的
     * STQ1_0 / Q2_0c 量化都依赖未合入主线的 llama.cpp PR（#22836 / #19357），主线 master
     * 无法加载，已从枚举里移除避免误导用户。旧 settings 里残留的 "LOCAL_HUNYUAN_MT" 字符串
     * 由 SettingsRepository.toSettings() 的 runCatching{...}.getOrDefault(OPENAI) 兜底。
     */
    LOCAL_SAKURA,
    /**
     * 端侧 LLM 翻译 —— Tencent Hy-MT2-1.8B Q4_K_M（约 1.13 GB），多语种翻译专用。
     * 走 [com.gameocr.app.llm.LlamaEngineHolder] + llama.cpp（com.arm.aichat binding）。
     * 仅 Android 13+ 可用；语言方向跟随 [sourceLang] / [targetLang]。
     */
    LOCAL_HY_MT2
}

/** 端侧 LLM 下载源选择。see [Settings.localLlmMirror]。 */
@Serializable
enum class LlmMirrorChoice {
    /** huggingface.co 官方原站，海外或带代理时首选。 */
    HF_OFFICIAL,
    /**
     * 国内可直连镜像源：
     * - Hy-MT2 / Sakura → hf-mirror.com。
     */
    HF_MIRROR,
    /** 用户自定义 base URL，配 [Settings.localLlmMirrorUrl] 用。 */
    CUSTOM,
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
    UMI_OCR,          // Umi-OCR HTTP service running on LAN/PC.
    LUNA_OCR,         // LunaTranslator HTTP service running on LAN/PC.
    ML_KIT_AUTO,      // 自动选 latin / 日 / 韩 / 中（按文字类型探测）
    ML_KIT_LATIN,
    ML_KIT_JAPANESE,
    ML_KIT_CHINESE,
    ML_KIT_KOREAN,    // ML Kit 韩文识别器（端侧、~20MB 模型按需下载）
    BAIDU,            // 百度通用文字识别（云端，需要 API Key + Secret）
    TENCENT,          // 腾讯云 GeneralBasicOCR（云端，需要 SecretId + SecretKey）
    YOUDAO,           // 有道智云通用文字识别 ocrapi（云端，需要 AppKey + AppSecret）
    PADDLE_AI_STUDIO, // PaddleOCR AI Studio async jobs API (cloud, requires bearer token)
    PADDLE_ONNX,      // PaddleOCR PP-OCRv5 mobile (ONNX Runtime 端侧，按需下载模型)
    MANGA_OCR_JA      // l0wgear/manga-ocr-2025-onnx 日漫专用（端侧；复用 PaddleOCR DBNet 检测；~140MB 按需下载，需开代理）
}

/**
 * 此引擎是否要求 [CaptureService] 跳过 [PreprocessOptions.invert] / [PreprocessOptions.binarize]
 * 预处理，传入接近原图的 bitmap。MANGA_OCR_JA 训练时见的是漫画原图（含网点、灰阶），
 * 二值化后效果显著下降，因此走 raw 路径。其它引擎默认走完整预处理链。
 *
 * 注：[PreprocessOptions.upscale2x] 仍会应用——DBNet 对小字检测有帮助，对 manga-ocr 224×224
 * squash resize 后无副作用。
 */
val OcrEngineKind.needsRawBitmap: Boolean
    get() = this == OcrEngineKind.MANGA_OCR_JA

fun Settings.dbnetUnclipRatioFor(engine: OcrEngineKind): Float =
    if (engine == OcrEngineKind.MANGA_OCR_JA) mangaOcrDbnetUnclipRatio else dbnetUnclipRatio

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
    /** 可拖拽 / 可缩放的悬浮窗口，列出所有原文 → 译文。0.3.x 之前叫 BANNER（屏幕底部整条横幅）。 */
    FLOATING_WINDOW
}

/** 悬浮窗口（[RenderMode.FLOATING_WINDOW]）的内容形态。 */
@Serializable
enum class FloatingWindowContentMode {
    /** 每段「原文 + 译文」上下排列。 */
    SRC_AND_DST,
    /** 仅显示译文，段间用分隔线。更紧凑。 */
    DST_ONLY
}

/**
 * 悬浮窗口边框样式。SOLID 是默认（跟 CSS `border-style: solid` 等价）。
 * 仅在主题本身有 stroke 时生效（AMBER_GOLD / PAPER_LIGHT / FROST_GLASS / CUSTOM with width>0）；
 * CLASSIC_DARK 默认无边，选啥样式都不画。
 */
@Serializable
enum class BorderStyle {
    SOLID,
    DASHED,
    DOTTED,
    DOUBLE,
    GROOVE
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
