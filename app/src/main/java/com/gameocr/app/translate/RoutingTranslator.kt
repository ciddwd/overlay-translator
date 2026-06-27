package com.gameocr.app.translate

import android.graphics.Bitmap
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.ocr.TextBlock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** 按 [Settings.translatorEngine] 路由到 OpenAI 兼容 / DeepL / 有道图片翻译 / Google /
 *  火山 / 百度翻译 / 腾讯云翻译。新增引擎只需在此加构造参数 + [engineFor] 的 when 分支。 */
@Singleton
class RoutingTranslator @Inject constructor(
    private val openAi: OpenAiTranslator,
    private val deepl: DeepLTranslator,
    private val youdaoPicTrans: YoudaoPicTransTranslator,
    private val google: GoogleTranslator,
    private val volc: VolcTranslator,
    private val baiduFanyi: BaiduFanyiTranslator,
    private val tencent: TencentTranslator
) : Translator {
    override suspend fun translate(source: String, settings: Settings): String? =
        engineFor(settings).translate(source, settings)

    override fun translateStream(source: String, settings: Settings): Flow<String> =
        engineFor(settings).translateStream(source, settings)

    /** RoutingTranslator 把 prefersBatch 直接转发到当前 settings 选中的引擎。 */
    override val prefersBatch: Boolean
        get() = false // 不能静态判断；调用方应该用 [prefersBatchFor]

    fun prefersBatchFor(settings: Settings): Boolean = engineFor(settings).prefersBatch

    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> =
        engineFor(settings).translateBatch(sources, settings)

    override suspend fun testConnection(settings: Settings): TestResult =
        engineFor(settings).testConnection(settings)

    override val isEndToEnd: Boolean get() = false
    /** RoutingTranslator 的 isEndToEnd 不能静态判，调用方应用 [isEndToEndFor]。 */
    fun isEndToEndFor(settings: Settings): Boolean = engineFor(settings).isEndToEnd

    override suspend fun ocrAndTranslate(
        bitmap: Bitmap,
        settings: Settings
    ): List<Pair<TextBlock, String>> =
        engineFor(settings).ocrAndTranslate(bitmap, settings)

    private fun engineFor(settings: Settings): Translator = when (settings.translatorEngine) {
        TranslatorEngine.OPENAI -> openAi
        TranslatorEngine.DEEPL -> deepl
        TranslatorEngine.YOUDAO_PICTRANS -> youdaoPicTrans
        TranslatorEngine.GOOGLE -> google
        TranslatorEngine.VOLC -> volc
        TranslatorEngine.BAIDU_FANYI -> baiduFanyi
        TranslatorEngine.TENCENT -> tencent
    }
}
