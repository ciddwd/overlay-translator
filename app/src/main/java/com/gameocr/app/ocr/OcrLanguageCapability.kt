package com.gameocr.app.ocr

import com.gameocr.app.data.BaiduOcrEndpoint
import com.gameocr.app.data.BaiduOcrLanguage
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TencentOcrEndpoint
import com.gameocr.app.data.TencentOcrLanguage

/**
 * "源语言 ↔ OCR 引擎能力" 单一事实源。
 *
 * 给 UI / 联动逻辑回答两个问题：
 *  1. [supports]: 当前 Settings 下的 OCR 引擎，能不能识别给定 BCP-47 源语言？
 *  2. [recommendFor]: 对给定源语言，应当切到哪个引擎 + 哪个端点 + 哪个 language_type？
 *
 * 源语言切换时 SettingsScreen 调用 supports 判断当前组合是否够用；不够则提示用户切到 recommend。
 */
object OcrLanguageCapability {

    /**
     * ML Kit Latin recognizer 实际能识别的语言。该 recognizer 基于拉丁字母 OCR
     * 训练，对俄/希腊/阿拉伯/泰/印地等非拉丁字母无能为力。
     */
    private val ML_KIT_LATIN_LANGS = setOf(
        "en", "fr", "es", "de", "it", "pt", "nl", "sv", "da", "nb", "no",
        "fi", "pl", "ro", "tr", "id", "vi", "hu", "cs", "sk", "hr", "et",
        "lv", "lt", "sl", "ms", "ca", "is", "ga", "cy"
    )

    /** PaddleOCR PP-OCRv5 mobile rec 字典涵盖的语言（中英日融合）。韩 / 拉丁系扩展暂未打包。 */
    private val PADDLE_V5_LANGS = setOf("zh", "zh-CN", "zh-TW", "en", "ja")

    /** 有道智云 OCR (ocrapi) 支持的 langType 对应 BCP-47 集合。 */
    private val YOUDAO_LANGS = setOf("zh", "zh-CN", "zh-TW", "en", "ja", "ko", "fr", "de", "es", "ru", "pt", "it")

    /** ML Kit 自动模式实际可命中的语言集合（含 latin 子集）。 */
    private val UMI_OCR_LANGS = setOf("zh", "zh-CN", "zh-TW", "en", "ja", "ko", "ru")

    private val ML_KIT_AUTO_LANGS = ML_KIT_LATIN_LANGS + setOf("ja", "ko", "zh", "zh-CN", "zh-TW")

    /** 完整 Settings 版本：内部委托给轻量重载。 */
    fun supports(settings: Settings, sourceCode: String): Boolean = supports(
        engine = settings.ocrEngine,
        sourceCode = sourceCode,
        baiduEndpoint = settings.baiduOcrEndpoint,
        tencentEndpoint = settings.tencentOcrEndpoint,
        baiduLanguage = settings.baiduOcrLanguage,
        tencentLanguage = settings.tencentOcrLanguage
    )

    /**
     * 轻量重载：只依赖 [engine] / [baiduEndpoint] / [tencentEndpoint] / 当前 language 状态，方便
     * UI 用 Compose state 直接调用。
     *
     * "auto" 源语言：要求 OCR 也在"自动 / 通用"模式（[isInAutoMode]）；否则视为不匹配。
     */
    fun supports(
        engine: OcrEngineKind,
        sourceCode: String,
        baiduEndpoint: BaiduOcrEndpoint = BaiduOcrEndpoint.GENERAL,
        tencentEndpoint: TencentOcrEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
        baiduLanguage: BaiduOcrLanguage = BaiduOcrLanguage.CHN_ENG,
        tencentLanguage: TencentOcrLanguage = TencentOcrLanguage.AUTO
    ): Boolean {
        val code = normalize(sourceCode)
        if (code.isEmpty()) return true
        if (code == "auto") {
            return isInAutoMode(engine, baiduEndpoint, baiduLanguage, tencentEndpoint, tencentLanguage)
        }

        return when (engine) {
            OcrEngineKind.ML_KIT_AUTO -> code in ML_KIT_AUTO_LANGS
            OcrEngineKind.ML_KIT_LATIN -> code in ML_KIT_LATIN_LANGS
            OcrEngineKind.ML_KIT_JAPANESE -> code == "ja"
            OcrEngineKind.ML_KIT_KOREAN -> code == "ko"
            OcrEngineKind.ML_KIT_CHINESE -> code.startsWith("zh")
            OcrEngineKind.PADDLE_ONNX -> code in PADDLE_V5_LANGS
            OcrEngineKind.BAIDU -> baiduSupports(baiduEndpoint, baiduLanguage, code)
            OcrEngineKind.TENCENT -> tencentSupports(tencentEndpoint, tencentLanguage, code)
            // 有道 OCR 由 sourceLang 直接映射 langType，覆盖 zh/en/ja/ko/fr/de/es/ru/pt/it/auto
            OcrEngineKind.YOUDAO -> code in YOUDAO_LANGS
            OcrEngineKind.UMI_OCR -> code in UMI_OCR_LANGS
            OcrEngineKind.LUNA_OCR -> true
            // manga-ocr 仅训练日文，专门处理日漫气泡
            OcrEngineKind.MANGA_OCR_JA -> code == "ja"
        }
    }

    /**
     * OCR 是否处于"自动 / 通用"模式，跟"源语言=auto"语义匹配：
     * - ML_KIT_AUTO ✓（按 Hangul/假名/拉丁 自动切识别器）
     * - PADDLE_ONNX ✓（v5 字典本来就是多语种融合）
     * - 固定语种的 ML Kit 单识别器（latin/ja/ko/zh） ✗
     * - 百度 / 腾讯：要求 language 也处于 auto 类语义
     */
    private fun isInAutoMode(
        engine: OcrEngineKind,
        baiduEndpoint: BaiduOcrEndpoint,
        baiduLanguage: BaiduOcrLanguage,
        tencentEndpoint: TencentOcrEndpoint,
        tencentLanguage: TencentOcrLanguage
    ): Boolean = when (engine) {
        OcrEngineKind.ML_KIT_AUTO -> true
        OcrEngineKind.PADDLE_ONNX -> true
        // 有道 OCR 支持 langType=auto，可视作通用模式
        OcrEngineKind.YOUDAO -> true
        OcrEngineKind.UMI_OCR -> true
        OcrEngineKind.LUNA_OCR -> true
        OcrEngineKind.ML_KIT_LATIN,
        OcrEngineKind.ML_KIT_JAPANESE,
        OcrEngineKind.ML_KIT_KOREAN,
        OcrEngineKind.ML_KIT_CHINESE,
        OcrEngineKind.MANGA_OCR_JA -> false  // 专门固定到 ja，不是 auto
        OcrEngineKind.BAIDU -> when {
            // 含位置 / webimage 不读 language_type → 默认中英；用户切 auto 时这视为"通用"
            !baiduEndpoint.acceptsLanguageType -> true
            else -> baiduLanguage == BaiduOcrLanguage.AUTO_DETECT ||
                baiduLanguage == BaiduOcrLanguage.CHN_ENG
        }
        OcrEngineKind.TENCENT -> when {
            // GeneralAccurate / RecognizeAgent 不读 LanguageType → 自动判断
            !tencentEndpoint.acceptsLanguageType -> true
            else -> tencentLanguage == TencentOcrLanguage.AUTO ||
                tencentLanguage == TencentOcrLanguage.MIX
        }
    }

    /**
     * 百度对 [code] 的实际可识别情况，**取决于当前 baiduLanguage**——光端点理论上支持
     * 是不够的，用户必须把 language 切到对应值才能真识别。
     *
     * 判断顺序：
     *  1. 端点不读 language_type（webimage）→ 永远按中英混合走，只支持 zh / en
     *  2. 当前 language 已经精确匹配 BCP-47 → 支持
     *  3. 当前 language = AUTO_DETECT（仅高精度系可选）→ 看 endpoint 能不能识别该 BCP-47
     *  4. 当前 language = CHN_ENG → 只支持 zh / en
     *  5. 其它情况：language 是别的具体语种但跟 code 不匹配 → 不支持
     */
    private fun baiduSupports(
        endpoint: BaiduOcrEndpoint,
        language: BaiduOcrLanguage,
        code: String
    ): Boolean {
        if (!endpoint.acceptsLanguageType) {
            return code == "en" || code.startsWith("zh")
        }
        if (language.bcp47 == code) return true
        if (language == BaiduOcrLanguage.AUTO_DETECT) {
            val match = BaiduOcrLanguage.entries.firstOrNull { it.bcp47 == code }
            return match != null && match.supportedOn(endpoint)
        }
        if (language == BaiduOcrLanguage.CHN_ENG) {
            return code == "en" || code.startsWith("zh")
        }
        return false
    }

    /**
     * 腾讯对 [code] 的可识别情况，同样取决于当前 tencentLanguage：
     *  1. 端点不读 LanguageType（GeneralAccurate / RecognizeAgent）→ 看端点默认能力（zh / en 兜底）
     *  2. 当前 language 精确匹配 BCP-47 → 支持
     *  3. 当前 language = AUTO 或 MIX → 看端点是否覆盖该 BCP-47
     *  4. 当前 language = ZH / ZH_RARE → 只支持 zh / en
     *  5. 其它情况：不支持
     */
    private fun tencentSupports(
        endpoint: TencentOcrEndpoint,
        language: TencentOcrLanguage,
        code: String
    ): Boolean {
        if (!endpoint.acceptsLanguageType) {
            return code == "en" || code.startsWith("zh")
        }
        if (language.bcp47 == code) return true
        if (language == TencentOcrLanguage.AUTO || language == TencentOcrLanguage.MIX) {
            // 腾讯 GeneralBasic + auto/mix 默认就识别中英文。TencentOcrLanguage 枚举里没有
            // 显式的 EN/ZH 项（腾讯 API 没暴露独立 en 值），需要在这里特判，否则 supports("en")
            // 会因 entries 里找不到 bcp47="en" 而误判 false。
            if (code == "en" || code.startsWith("zh")) return true
            val match = TencentOcrLanguage.entries.firstOrNull { it.bcp47 == code }
            return match != null && match.supportedOn(endpoint)
        }
        if (language == TencentOcrLanguage.ZH || language == TencentOcrLanguage.ZH_RARE) {
            return code == "en" || code.startsWith("zh")
        }
        return false
    }

    /**
     * 给定源语言，推荐"该用哪个 OCR 引擎 + 配置"。优先级：
     *  1. 用户已在云端 + key 已配 → 保留引擎，只切 language（避免把已经付费的用户推回端侧）
     *  2. ML Kit 端侧（免费 + 离线）—— 覆盖 ja / ko / zh + 拉丁系
     *  3. PaddleOCR —— 覆盖 zh/en/ja（如果用户已下载模型）
     *  4. 百度 accurate_basic（25 种最全）—— 用户在端侧但端侧不支持的语言
     *  5. 腾讯 GeneralBasicOCR（23 种）
     *
     * 不强制 push 用户切到云端：[current] 用来检查 key 是否填了，没填就不推荐对应云端。
     * 返回 null 表示没有任何引擎能识别这个语言。
     */
    fun recommendFor(sourceCode: String, current: Settings): Recommendation? = recommendFor(
        sourceCode = sourceCode,
        currentEngine = current.ocrEngine,
        currentBaiduEndpoint = current.baiduOcrEndpoint,
        currentTencentEndpoint = current.tencentOcrEndpoint,
        hasBaiduKey = current.baiduOcrApiKey.isNotBlank() && current.baiduOcrSecretKey.isNotBlank(),
        hasTencentKey = current.tencentSecretId.isNotBlank() && current.tencentSecretKey.isNotBlank()
    )

    /**
     * 轻量重载：UI 直接传当前引擎 + 是否已配 key，避免凑完整 [Settings]。
     *
     * [currentEngine] 用来在 "auto" 推荐时尽量"保留当前引擎"——比如用户已经在百度上花了 key，
     * 切 auto 时给他把 baiduLanguage 切到 AUTO_DETECT 而不是强行换到 ML Kit。
     */
    fun recommendFor(
        sourceCode: String,
        currentEngine: OcrEngineKind = OcrEngineKind.ML_KIT_AUTO,
        currentBaiduEndpoint: BaiduOcrEndpoint = BaiduOcrEndpoint.GENERAL,
        currentTencentEndpoint: TencentOcrEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
        hasBaiduKey: Boolean = false,
        hasTencentKey: Boolean = false
    ): Recommendation? {
        val code = normalize(sourceCode)
        if (code.isEmpty()) return null

        // 0) 源语言 = auto：保留当前引擎 + 把 language 切到"自动 / 通用"
        if (code == "auto") return autoModeRecommendation(
            currentEngine, currentBaiduEndpoint, currentTencentEndpoint, hasBaiduKey, hasTencentKey
        )

        // 1.a) 当前已在 manga-ocr 且源语言 = ja → 保留（专家模式，避免被推回 ML Kit）
        //      与百度 / 腾讯保留已配引擎逻辑对称
        if (currentEngine == OcrEngineKind.MANGA_OCR_JA && code == "ja") {
            return Recommendation(OcrEngineKind.MANGA_OCR_JA)
        }

        // 1) 优先级最高：用户已在云端 + key 已配 → 保留引擎，只切 language（跟 auto 策略对称，
        //    避免用户花了 key 反而被推回 ML Kit）
        if (currentEngine == OcrEngineKind.BAIDU && hasBaiduKey) {
            val baiduMatch = BaiduOcrLanguage.entries.firstOrNull { it.bcp47 == code }
            if (baiduMatch != null) {
                val endpoint = when {
                    baiduMatch.supportedOn(currentBaiduEndpoint) -> currentBaiduEndpoint
                    baiduMatch.supportedOn(BaiduOcrEndpoint.ACCURATE_BASIC) -> BaiduOcrEndpoint.ACCURATE_BASIC
                    else -> BaiduOcrEndpoint.GENERAL_BASIC
                }
                return Recommendation(
                    engine = OcrEngineKind.BAIDU,
                    baiduEndpoint = endpoint,
                    baiduLanguage = baiduMatch
                )
            }
        }
        if (currentEngine == OcrEngineKind.TENCENT && hasTencentKey) {
            val tencentMatch = TencentOcrLanguage.entries.firstOrNull { it.bcp47 == code }
            if (tencentMatch != null) {
                return Recommendation(
                    engine = OcrEngineKind.TENCENT,
                    tencentEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
                    tencentLanguage = tencentMatch
                )
            }
        }

        // 2) ML Kit 端侧（用户不在云端 / key 未配 / 当前云端不支持该语种）
        when {
            code == "ja" -> return Recommendation(OcrEngineKind.ML_KIT_JAPANESE)
            code == "ko" -> return Recommendation(OcrEngineKind.ML_KIT_KOREAN)
            code.startsWith("zh") -> return Recommendation(OcrEngineKind.ML_KIT_CHINESE)
            code in ML_KIT_LATIN_LANGS -> return Recommendation(OcrEngineKind.ML_KIT_LATIN)
        }

        // 2) 云端兜底（仅当用户已配 key）：accurate_basic / GeneralBasicOCR
        val baiduMatch = BaiduOcrLanguage.entries.firstOrNull { it.bcp47 == code }
        if (hasBaiduKey && baiduMatch != null) {
            // 选 accurate_basic 因为它支持的语种最多
            val endpoint = if (baiduMatch.supportedOn(BaiduOcrEndpoint.ACCURATE_BASIC))
                BaiduOcrEndpoint.ACCURATE_BASIC
            else BaiduOcrEndpoint.GENERAL_BASIC
            return Recommendation(
                engine = OcrEngineKind.BAIDU,
                baiduEndpoint = endpoint,
                baiduLanguage = baiduMatch
            )
        }

        val tencentMatch = TencentOcrLanguage.entries.firstOrNull { it.bcp47 == code }
        if (hasTencentKey && tencentMatch != null) {
            return Recommendation(
                engine = OcrEngineKind.TENCENT,
                tencentEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
                tencentLanguage = tencentMatch
            )
        }

        // 用户没配云端 key，但该语言只有云端支持 → 引导用户去配
        if (baiduMatch != null) return Recommendation(
            engine = OcrEngineKind.BAIDU,
            baiduEndpoint = BaiduOcrEndpoint.ACCURATE_BASIC,
            baiduLanguage = baiduMatch,
            keysMissing = true
        )
        if (tencentMatch != null) return Recommendation(
            engine = OcrEngineKind.TENCENT,
            tencentEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
            tencentLanguage = tencentMatch,
            keysMissing = true
        )

        return null
    }

    /**
     * "源语言 = auto" 时的推荐：
     * - 当前在百度且 key 已配 → 保留百度，切到支持 language_type 的 endpoint + AUTO_DETECT
     * - 当前在腾讯且 key 已配 → 保留腾讯，切到 GENERAL_BASIC + AUTO
     * - 当前已经是 ML_KIT_AUTO 或 PADDLE_ONNX → 不推荐（已经处于自动）
     * - 其它 ML Kit 单语种识别器 → 推荐 ML_KIT_AUTO
     */
    private fun autoModeRecommendation(
        currentEngine: OcrEngineKind,
        currentBaiduEndpoint: BaiduOcrEndpoint,
        currentTencentEndpoint: TencentOcrEndpoint,
        hasBaiduKey: Boolean,
        hasTencentKey: Boolean
    ): Recommendation? = when (currentEngine) {
        OcrEngineKind.ML_KIT_AUTO,
        OcrEngineKind.PADDLE_ONNX,
        OcrEngineKind.UMI_OCR,
        OcrEngineKind.LUNA_OCR -> null
        // manga-ocr 在 sourceLang=auto 时不合适（仅训练日文），推回 ML_KIT_AUTO
        OcrEngineKind.MANGA_OCR_JA -> Recommendation(OcrEngineKind.ML_KIT_AUTO)
        OcrEngineKind.BAIDU -> if (hasBaiduKey) {
            // 含位置 / webimage 不读 language_type，没必要硬切端点；保留用户选择
            val endpoint = if (currentBaiduEndpoint.acceptsLanguageType) currentBaiduEndpoint
            else BaiduOcrEndpoint.ACCURATE_BASIC
            Recommendation(
                engine = OcrEngineKind.BAIDU,
                baiduEndpoint = endpoint,
                baiduLanguage = BaiduOcrLanguage.AUTO_DETECT
            )
        } else {
            // key 没填了，干脆推荐 ML_KIT_AUTO
            Recommendation(OcrEngineKind.ML_KIT_AUTO)
        }
        OcrEngineKind.TENCENT -> if (hasTencentKey) {
            // GeneralAccurate / RecognizeAgent 不读 LanguageType，等价"自动"；保留就好
            if (!currentTencentEndpoint.acceptsLanguageType) {
                // 已经是不读 LanguageType 的端点，相当于 auto，不需要推荐
                null
            } else {
                Recommendation(
                    engine = OcrEngineKind.TENCENT,
                    tencentEndpoint = TencentOcrEndpoint.GENERAL_BASIC,
                    tencentLanguage = TencentOcrLanguage.AUTO
                )
            }
        } else {
            Recommendation(OcrEngineKind.ML_KIT_AUTO)
        }
        // ML_KIT_LATIN / JAPANESE / KOREAN / CHINESE → 切到 ML_KIT_AUTO 是最优
        else -> Recommendation(OcrEngineKind.ML_KIT_AUTO)
    }

    /**
     * supports=true 但还有"更精确"的云端 language 可用 → 返回升级建议；否则 null。
     *
     * 背景：百度 AUTO_DETECT / CHN_ENG、腾讯 AUTO / MIX 等通用模式**理论上**能识别小语种
     * （supports 返回 true），但实测对韩 / 日 / 俄等的准确率明显低于精确指定 language。
     * 既然枚举里有精确项（百度 KOR / JAP、腾讯 KO / JA 等），就主动建议用户切到精确项。
     *
     * 调用约束：
     * - 端点不接受 language_type（百度 webimage）→ null（无 language 可调）
     * - 当前 language 已精确匹配 BCP-47 → null（已经最优）
     * - ML Kit / Paddle / 当前是非通用的具体 language → null
     */
    fun betterOcrLanguageFor(
        sourceCode: String,
        engine: OcrEngineKind,
        baiduEndpoint: BaiduOcrEndpoint,
        baiduLanguage: BaiduOcrLanguage,
        tencentEndpoint: TencentOcrEndpoint,
        tencentLanguage: TencentOcrLanguage
    ): Recommendation? {
        val code = normalize(sourceCode)
        if (code.isEmpty() || code == "auto") return null
        return when (engine) {
            OcrEngineKind.BAIDU -> {
                if (!baiduEndpoint.acceptsLanguageType) return null
                if (baiduLanguage.bcp47 == code) return null
                val isGeneric = baiduLanguage == BaiduOcrLanguage.AUTO_DETECT ||
                    baiduLanguage == BaiduOcrLanguage.CHN_ENG
                if (!isGeneric) return null
                val match = BaiduOcrLanguage.entries.firstOrNull { it.bcp47 == code }
                if (match != null && match.supportedOn(baiduEndpoint)) {
                    Recommendation(
                        engine = OcrEngineKind.BAIDU,
                        baiduEndpoint = baiduEndpoint,
                        baiduLanguage = match
                    )
                } else null
            }
            OcrEngineKind.TENCENT -> {
                if (!tencentEndpoint.acceptsLanguageType) return null
                if (tencentLanguage.bcp47 == code) return null
                val isGeneric = tencentLanguage == TencentOcrLanguage.AUTO ||
                    tencentLanguage == TencentOcrLanguage.MIX ||
                    tencentLanguage == TencentOcrLanguage.ZH ||
                    tencentLanguage == TencentOcrLanguage.ZH_RARE
                if (!isGeneric) return null
                val match = TencentOcrLanguage.entries.firstOrNull { it.bcp47 == code }
                if (match != null && match.supportedOn(tencentEndpoint)) {
                    Recommendation(
                        engine = OcrEngineKind.TENCENT,
                        tencentEndpoint = tencentEndpoint,
                        tencentLanguage = match
                    )
                } else null
            }
            else -> null
        }
    }

    /**
     * 反向推断：给定当前 OCR 配置，它正"针对"哪个 BCP-47 源语言？
     *
     * 用于源语言↔OCR 联动的"反向推荐"：用户主动改了 OCR 端（引擎 / 端点 / 内部识别语种）
     * 时，UI 应建议把源语言改成与该 OCR 配置匹配的值，而不是把用户刚改的 OCR 撤销。
     *
     * 多语种引擎（ML_KIT_AUTO / ML_KIT_LATIN / PADDLE_ONNX）和"自动 / 中英混合"等无单一对应
     * BCP-47 的 language 返回 null，UI 据此跳过反向推荐。
     */
    fun inferSourceFor(
        engine: OcrEngineKind,
        baiduLanguage: BaiduOcrLanguage,
        tencentLanguage: TencentOcrLanguage
    ): String? = when (engine) {
        OcrEngineKind.ML_KIT_JAPANESE -> "ja"
        OcrEngineKind.ML_KIT_KOREAN -> "ko"
        OcrEngineKind.ML_KIT_CHINESE -> "zh-CN"
        OcrEngineKind.ML_KIT_AUTO,
        OcrEngineKind.ML_KIT_LATIN,
        OcrEngineKind.PADDLE_ONNX,
        // 有道 OCR 由 sourceLang 反向映射 langType，没有单独的"OCR 内部语种"字段
        OcrEngineKind.YOUDAO,
        OcrEngineKind.UMI_OCR,
        OcrEngineKind.LUNA_OCR -> null
        OcrEngineKind.MANGA_OCR_JA -> "ja"
        OcrEngineKind.BAIDU -> baiduLanguage.bcp47
        OcrEngineKind.TENCENT -> tencentLanguage.bcp47
    }

    /** BCP-47 规范化：去 region tag（"ja-JP" → "ja"），保留 zh 的简繁差异。 */
    private fun normalize(code: String): String {
        val trimmed = code.trim()
        if (trimmed.startsWith("zh", ignoreCase = true)) {
            return when (trimmed.lowercase()) {
                "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "zh-CN"
                "zh-tw", "zh-hant", "zh-hant-tw" -> "zh-TW"
                else -> trimmed
            }
        }
        return trimmed.substringBefore('-').lowercase()
    }

    data class Recommendation(
        val engine: OcrEngineKind,
        val baiduEndpoint: BaiduOcrEndpoint? = null,
        val baiduLanguage: BaiduOcrLanguage? = null,
        val tencentEndpoint: TencentOcrEndpoint? = null,
        val tencentLanguage: TencentOcrLanguage? = null,
        /** 推荐云端但用户尚未配 key —— UI 应当引导跳转设置而不是直接切换。 */
        val keysMissing: Boolean = false
    )
}

/** 百度端点是否真正接受 language_type 参数（仅 webimage 不接受，其它四个都支持）。 */
val BaiduOcrEndpoint.acceptsLanguageType: Boolean
    get() = this != BaiduOcrEndpoint.WEBIMAGE

/** 腾讯端点是否真正接受 LanguageType 参数（仅 GeneralBasicOCR 接受）。 */
val TencentOcrEndpoint.acceptsLanguageType: Boolean
    get() = this == TencentOcrEndpoint.GENERAL_BASIC
