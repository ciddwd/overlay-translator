package com.gameocr.app.data

import kotlinx.serialization.Serializable

/** Speech recognition engines: three on-device sherpa-onnx families and one cloud provider. */
@Serializable
enum class AsrEngineKind(val isCloud: Boolean = false) {
    SENSE_VOICE,
    ZIPFORMER,
    WHISPER,
    /** Alibaba Cloud DashScope paraformer-realtime-v2 over WebSocket; uploads playback audio. */
    PARAFORMER_CLOUD(isCloud = true),
}

@Serializable
enum class AsrWhisperVariant {
    TINY,
    BASE,
}

@Serializable
enum class AsrCloudProvider { ALIYUN, TENCENT, VOLCENGINE, OPENAI_COMPATIBLE }

/** Explicit wire protocol; model-list membership never implies realtime support. */
@Serializable
enum class OpenAiAsrTransport { HTTP_TRANSCRIPTION, REALTIME_TRANSCRIPTION }

@Serializable
enum class TencentAsrModel(val modelId: String, val translatesSpeech: Boolean = false) {
    STANDARD("16k_zh"),
    GENERAL("16k_zh_en_2.0"),
    SPEAKER("16k_zh_en_speaker_2.0"),
    TRANSLATE_LITE("hunyuan-translation-lite", true),
    TRANSLATE_STANDARD("hunyuan-translation", true),
}

@Serializable(with = VolcAsrResourceSerializer::class)
enum class VolcAsrResource(val resourceId: String) {
    V2_DURATION("volc.seedasr.sauc.duration"),
    V2_CONCURRENT("volc.seedasr.sauc.concurrent"),
}

/** Preserve the billing mode when reading a previously selected 1.0 resource. */
object VolcAsrResourceSerializer : kotlinx.serialization.KSerializer<VolcAsrResource> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "VolcAsrResource", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: VolcAsrResource) =
        encoder.encodeString(value.name)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): VolcAsrResource =
        when (val value = decoder.decodeString()) {
            "V1_DURATION" -> VolcAsrResource.V2_DURATION
            "V1_CONCURRENT" -> VolcAsrResource.V2_CONCURRENT
            else -> VolcAsrResource.entries.firstOrNull { it.name == value }
                ?: throw kotlinx.serialization.SerializationException("Unknown speech resource")
        }
}

/** Stored as one encrypted, device-local credential record; never included in exports. */
@Serializable
data class AsrCloudConnections(
    val tencentAppId: String = "",
    val volcApiKey: String = "",
    val openAiBaseUrl: String = "",
    val openAiApiKey: String = "",
    val openAiModel: String = "",
    val openAiTransport: OpenAiAsrTransport = OpenAiAsrTransport.HTTP_TRANSCRIPTION,
    // Retain data from the withdrawn debug-only adapter inside this encrypted record.
    // These compatibility fields have no UI or request path.
    val glmBaseUrl: String = "",
    val glmApiKey: String = "",
    val glmModel: String = "",
    val tencentModel: TencentAsrModel = TencentAsrModel.GENERAL,
    val volcResource: VolcAsrResource = VolcAsrResource.V2_DURATION,
) {
    override fun toString(): String = "AsrCloudConnections(private)"
}

@Serializable
enum class AsrCloudModel(val modelId: String, val usesRealtimeProtocol: Boolean = false, val translatesSpeech: Boolean = false) {
    PARAFORMER("paraformer-realtime-v2"),
    QWEN_AUDIO_STREAMING("qwen-audio-3.1-asr-flash-streaming"),
    FUN_ASR("fun-asr-realtime"),
    QWEN_ASR_REALTIME("qwen3-asr-flash-realtime", usesRealtimeProtocol = true),
    QWEN_LIVE_TRANSLATE_38("qwen3.8-livetranslate-flash-realtime", usesRealtimeProtocol = true, translatesSpeech = true),
    QWEN_LIVE_TRANSLATE_35("qwen3.5-livetranslate-flash-realtime", usesRealtimeProtocol = true, translatesSpeech = true),
}

/** [code] is the language value passed to the recognizer; AUTO lets the model detect it. */
@Serializable
enum class AsrLanguage(val code: String) {
    AUTO("auto"),
    ZH("zh"),
    EN("en"),
    JA("ja"),
    KO("ko"),
    YUE("yue"),
    DE("de"),
    ES("es"),
    RU("ru"),
    FR("fr"),
    PT("pt"),
    TR("tr"),
    PL("pl"),
    CA("ca"),
    NL("nl"),
    AR("ar"),
    SV("sv"),
    IT("it"),
    ID("id"),
    HI("hi"),
    FI("fi"),
    VI("vi"),
    HE("he"),
    UK("uk"),
    EL("el"),
    MS("ms"),
    CS("cs"),
    RO("ro"),
    DA("da"),
    HU("hu"),
    TA("ta"),
    NO("no"),
    TH("th"),
    UR("ur"),
    HR("hr"),
    BG("bg"),
    LT("lt"),
    LA("la"),
    MI("mi"),
    ML("ml"),
    CY("cy"),
    SK("sk"),
    TE("te"),
    FA("fa"),
    LV("lv"),
    BN("bn"),
    SR("sr"),
    AZ("az"),
    SL("sl"),
    KN("kn"),
    ET("et"),
    MK("mk"),
    BR("br"),
    EU("eu"),
    IS("is"),
    HY("hy"),
    NE("ne"),
    MN("mn"),
    BS("bs"),
    KK("kk"),
    SQ("sq"),
    SW("sw"),
    GL("gl"),
    MR("mr"),
    PA("pa"),
    SI("si"),
    KM("km"),
    SN("sn"),
    YO("yo"),
    SO("so"),
    AF("af"),
    OC("oc"),
    KA("ka"),
    BE("be"),
    TG("tg"),
    SD("sd"),
    GU("gu"),
    AM("am"),
    YI("yi"),
    LO("lo"),
    UZ("uz"),
    FO("fo"),
    HT("ht"),
    PS("ps"),
    TK("tk"),
    NN("nn"),
    MT("mt"),
    SA("sa"),
    LB("lb"),
    MY("my"),
    BO("bo"),
    TL("tl"),
    MG("mg"),
    AS("as"),
    TT("tt"),
    HAW("haw"),
    LN("ln"),
    HA("ha"),
    BA("ba"),
    JW("jw"),
    SU("su"),
    NB("nb"),
    FIL("fil"),
    AST("ast"),
    CEB("ceb"),
    JV("jv"),
    KY("ky"),
    ZH_EN("zh_en"),
    ZH_TW("zh-TW"),
}

/** Verified language parameters: Whisper tokenizer; Alibaba model-specific client/SDK docs. */
object AsrLanguagePolicy {
    private fun languages(codes: String) = listOf(AsrLanguage.AUTO) +
        codes.split(" ").map { code -> AsrLanguage.entries.single { it.code == code } }

    val senseVoice = languages("zh en ja ko yue")
    val whisper = languages("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su")
    val paraformer = languages("zh en ja yue ko de fr ru")
    val qwenRealtime = languages("zh yue en ja de ko ru fr pt ar it es hi id th tr uk vi cs da fil fi is ms no pl sv")
    val streaming = languages("zh en ja ko vi th id ms tl hi ar fr de es pt ru it nl sv da fi no el pl cs hu ro bg hr sk")
    val liveTranslate = languages("zh en ar de fr es pt id it ko ru th vi ja tr hi ms nl ur nb sv da he fi pl is cs fil fa yue el af ast be bg bn bs ca ceb et gl gu hr hu jv kk kn ky lv mk ml mr pa ro sk sl sw tg az uk")

    fun options(engine: AsrEngineKind, cloudModel: AsrCloudModel = AsrCloudModel.PARAFORMER,
        provider: AsrCloudProvider = AsrCloudProvider.ALIYUN,
        connections: AsrCloudConnections = AsrCloudConnections()): List<AsrLanguage> = when (engine) {
        AsrEngineKind.SENSE_VOICE -> senseVoice
        AsrEngineKind.WHISPER -> whisper
        AsrEngineKind.ZIPFORMER -> emptyList()
        AsrEngineKind.PARAFORMER_CLOUD -> when (provider) {
            // These real-time protocols do not expose a manual source-language parameter.
            AsrCloudProvider.TENCENT -> when {
                connections.tencentModel == TencentAsrModel.STANDARD -> TencentSpeechLanguagePolicy.standard
                connections.tencentModel.translatesSpeech -> TencentSpeechLanguagePolicy.translation
                else -> listOf(AsrLanguage.AUTO)
            }
            AsrCloudProvider.VOLCENGINE -> listOf(AsrLanguage.AUTO)
            AsrCloudProvider.OPENAI_COMPATIBLE -> {
                val model = connections.openAiModel.lowercase()
                when {
                    "sensevoice" in model -> senseVoice
                    "qwen3-asr" in model -> qwenRealtime
                    "whisper" in model -> if ("large-v3" in model || "turbo" in model) whisper + AsrLanguage.YUE else whisper
                    // Generic endpoints are user-configured; expose the complete ISO language
                    // catalog, avoiding aliases. The selected server determines actual support.
                    else -> AsrLanguage.entries.filter { it !in setOf(AsrLanguage.JW, AsrLanguage.FIL, AsrLanguage.NB, AsrLanguage.ZH_EN, AsrLanguage.ZH_TW) }
                }
            }
            AsrCloudProvider.ALIYUN -> when (cloudModel) {
                AsrCloudModel.PARAFORMER -> paraformer
                AsrCloudModel.QWEN_AUDIO_STREAMING, AsrCloudModel.FUN_ASR -> streaming
                AsrCloudModel.QWEN_ASR_REALTIME -> qwenRealtime
                AsrCloudModel.QWEN_LIVE_TRANSLATE_35 -> liveTranslate
                AsrCloudModel.QWEN_LIVE_TRANSLATE_38 -> listOf(AsrLanguage.AUTO)
            }
        }
    }

    fun effective(engine: AsrEngineKind, language: AsrLanguage, cloudModel: AsrCloudModel = AsrCloudModel.PARAFORMER,
        provider: AsrCloudProvider = AsrCloudProvider.ALIYUN,
        connections: AsrCloudConnections = AsrCloudConnections()): AsrLanguage =
        options(engine, cloudModel, provider, connections).let { options ->
            language.takeIf { it in options } ?: options.firstOrNull() ?: AsrLanguage.AUTO
        }
}

object AsrDisplayPolicy {
    const val DEFAULT_RECORDS = 10
    const val MIN_RECORDS = 3
    const val MAX_RECORDS = 50

    fun clampRecords(value: Int): Int = value.coerceIn(MIN_RECORDS, MAX_RECORDS)
}

/** General 16 kHz engines and speech-translation pairs from Tencent's WebSocket specifications. */
object TencentSpeechLanguagePolicy {
    val standard = "zh zh-TW en yue ko ja th id vi ms fil pt tr ar es hi fr de".split(" ").map { code -> AsrLanguage.entries.single { it.code == code } }
    val translation = listOf(AsrLanguage.ZH, AsrLanguage.EN, AsrLanguage.ZH_EN, AsrLanguage.JA,
        AsrLanguage.KO, AsrLanguage.YUE, AsrLanguage.ID, AsrLanguage.TH, AsrLanguage.RU)
    fun standardEngine(language: AsrLanguage): String {
        require(language in standard)
        return "16k_${language.code}"
    }
    fun target(value: String): String = value.trim().lowercase(java.util.Locale.ROOT).let {
        if (it == "zh_en") it else it.replace('_', '-').substringBefore('-')
    }
    fun supports(source: AsrLanguage, targetValue: String): Boolean {
        val allowed = when (source) {
            AsrLanguage.ZH, AsrLanguage.EN -> setOf("zh", "en", "ja", "ko", "yue", "id", "th")
            AsrLanguage.ZH_EN -> setOf("zh_en", "zh", "en", "ja", "ko", "yue", "id", "th")
            AsrLanguage.JA, AsrLanguage.KO, AsrLanguage.YUE -> setOf("zh", "en", "ja", "ko", "yue")
            AsrLanguage.ID -> setOf("zh", "en", "id")
            AsrLanguage.TH -> setOf("zh", "en", "th")
            AsrLanguage.RU -> setOf("zh", "en", "ru")
            else -> emptySet()
        }
        return target(targetValue) in allowed
    }
}
