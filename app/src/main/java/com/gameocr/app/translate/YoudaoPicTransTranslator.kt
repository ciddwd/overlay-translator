package com.gameocr.app.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.YoudaoOcrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * 有道智云图片翻译（ocrtransapi）——**端到端引擎**，OCR + 翻译一起出。
 *
 * 端点：https://openapi.youdao.com/ocrtransapi
 * 签名：复用 [YoudaoOcrEngine.youdaoV3Sign]
 * 响应：resRegions[]，每段含 boundingBox（"x,y,w,h" 四值！不同于 OCR 八值）、context（原文）、
 *      tranContent（译文）、linesCount/lineheight。
 *
 * [translate]/[translateStream] 走老的 Translator 接口在端到端模式下不被 CaptureService 调用；
 * 若误调返回错误提示。
 */
@Singleton
class YoudaoPicTransTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json
) : Translator {

    override val isEndToEnd: Boolean get() = true

    override suspend fun translate(source: String, settings: Settings): String? =
        throw TranslationException("有道图片翻译是端到端引擎，不支持单段文本翻译")

    override fun translateStream(source: String, settings: Settings): Flow<String> = emptyFlow()

    /**
     * 测试连接：发一张 2x2 像素的 JPEG 试探 ocrtransapi，按 errorCode 判 key 是否有效。
     * 不消耗实际识别配额（小图 / 没文字），但能验证签名 + key + 服务开通状态。
     */
    override suspend fun testConnection(settings: Settings): TestResult {
        if (settings.youdaoAppKey.isBlank() || settings.youdaoAppSecret.isBlank()) {
            return TestResult(false, "有道 AppKey/AppSecret 未配置")
        }
        val tinyImg = withContext(Dispatchers.Default) {
            val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
                bmp.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        val salt = java.util.UUID.randomUUID().toString()
        val curtime = (System.currentTimeMillis() / 1000L).toString()
        val sign = YoudaoOcrEngine.youdaoV3Sign(
            settings.youdaoAppKey, settings.youdaoAppSecret, tinyImg, salt, curtime
        )
        val form = FormBody.Builder()
            .add("q", tinyImg)
            .add("from", "auto").add("to", "zh-CHS")
            .add("type", "1").add("docType", "json").add("render", "0")
            .add("appKey", settings.youdaoAppKey)
            .add("salt", salt).add("curtime", curtime)
            .add("sign", sign).add("signType", "v3")
            .build()
        val req = Request.Builder()
            .url("https://openapi.youdao.com/ocrtransapi").post(form).build()
        val timed = client.withApiTimeout(settings.apiTimeoutSeconds)
        return runCatching {
            withContext(Dispatchers.IO) {
                timed.newCall(req).execute().use { r ->
                    val raw = r.body?.string().orEmpty()
                    if (!r.isSuccessful) return@use TestResult(false, "HTTP ${r.code}: ${raw.take(200)}")
                    val parsed = runCatching {
                        json.decodeFromString<TestResp>(raw)
                    }.getOrNull() ?: return@use TestResult(false, "解析失败: ${raw.take(200)}")
                    // 错误码参考有道公共错误码表，挑常用的；其余按 fail 兜底
                    when (parsed.errorCode) {
                        "0" -> TestResult(true, "OK 有道图翻可用")
                        // 401 账户欠费 / 411 访问频率受限 / 412 长 query 频率受限：服务侧拒绝
                        // 但说明 key 已通过认证，记为成功
                        "411", "412" -> TestResult(true, "OK key 有效，当前触发限流 (errorCode=${parsed.errorCode})")
                        "401" -> TestResult(false, "账户欠费 (errorCode=401)")
                        "108", "109", "202", "203", "205" ->
                            TestResult(false, "AppKey/Secret 无效或签名错 (errorCode=${parsed.errorCode})")
                        "110" -> TestResult(false, "未开通图片翻译服务 (errorCode=110)")
                        "111" -> TestResult(false, "开发者账户无效 (errorCode=111)")
                        else -> TestResult(false, "errorCode=${parsed.errorCode}: ${raw.take(200)}")
                    }
                }
            }
        }.getOrElse { TestResult(false, it.message ?: it.javaClass.simpleName) }
    }

    @kotlinx.serialization.Serializable
    private data class TestResp(val errorCode: String? = null)

    override suspend fun ocrAndTranslate(
        bitmap: Bitmap,
        settings: Settings
    ): List<Pair<TextBlock, String>> {
        if (settings.youdaoAppKey.isBlank() || settings.youdaoAppSecret.isBlank()) {
            throw TranslationException("有道 AppKey/AppSecret 未配置")
        }
        val srcW = bitmap.width
        val srcH = bitmap.height
        val imgB64 = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        val salt = UUID.randomUUID().toString()
        val curtime = (System.currentTimeMillis() / 1000L).toString()
        val sign = YoudaoOcrEngine.youdaoV3Sign(
            settings.youdaoAppKey, settings.youdaoAppSecret, imgB64, salt, curtime
        )
        val from = mapLang(settings.sourceLang)
        val to = mapLang(settings.targetLang)
        Timber.tag("YoudaoPicTrans").i(
            "[req] sourceLang=%s targetLang=%s -> from=%s to=%s imgSize=%dKB srcImg=%dx%d",
            settings.sourceLang, settings.targetLang, from, to, imgB64.length / 1024, srcW, srcH
        )
        val form = FormBody.Builder()
            .add("q", imgB64)
            .add("from", from)
            .add("to", to)
            .add("type", "1")
            .add("docType", "json")
            .add("render", "0")  // 不需要返回带译文的合成图（省带宽）
            .add("appKey", settings.youdaoAppKey)
            .add("salt", salt)
            .add("curtime", curtime)
            .add("sign", sign)
            .add("signType", "v3")
            .build()
        val req = Request.Builder()
            .url("https://openapi.youdao.com/ocrtransapi")
            .post(form).build()
        val timed = client.withApiTimeout(settings.apiTimeoutSeconds)
        return withContext(Dispatchers.IO) {
            timed.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    throw TranslationException("有道图片翻译 HTTP ${r.code}: ${raw.take(200)}")
                }
                val parsed = runCatching { json.decodeFromString<PicTransResp>(raw) }
                    .getOrElse {
                        throw TranslationException("有道图片翻译解析失败: ${raw.take(200)}", it)
                    }
                if (parsed.errorCode != "0") {
                    throw TranslationException("有道图片翻译 errorCode=${parsed.errorCode}: ${raw.take(200)}")
                }
                Timber.tag("YoudaoPicTrans").i(
                    "[resp] lanFrom=%s lanTo=%s regions=%d orientation=%s",
                    parsed.lanFrom, parsed.lanTo, parsed.resRegions?.size ?: 0, parsed.orientation
                )
                parsed.resRegions.orEmpty().mapIndexed { i, region ->
                    val rawBox = parseXywhBox(region.boundingBox)
                    val box = applyOrientation(rawBox, parsed.orientation, srcW, srcH)
                    val src = region.context.orEmpty().trim()
                    val dst = region.tranContent.orEmpty().trim()
                    Timber.tag("YoudaoPicTrans").i(
                        "[box] #%d raw(%d,%d,%d,%d) -> screen(%d,%d,%d,%d) %dx%d  src=%s",
                        i + 1, rawBox.left, rawBox.top, rawBox.right, rawBox.bottom,
                        box.left, box.top, box.right, box.bottom, box.width(), box.height(),
                        src.take(40)
                    )
                    TextBlock(
                        text = src,
                        boundingBox = box,
                        confidence = 1f,
                        recognizedLanguage = parsed.lanFrom ?: "auto"
                    ) to dst
                }
            }
        }
    }

    /** sourceLang/targetLang BCP-47 → 有道图片翻译 from/to。 */
    private fun mapLang(s: String): String {
        val l = s.lowercase()
        if (l == "auto") return "auto"
        if (l == "zh-cn" || l == "zh") return "zh-CHS"
        if (l == "zh-tw" || l == "zh-hant") return "zh-CHT"
        val core = l.substringBefore('-')
        return when (core) {
            "en" -> "en"
            "ja" -> "ja"
            "ko" -> "ko"
            "fr" -> "fr"
            "de" -> "de"
            "es" -> "es"
            "ru" -> "ru"
            "pt" -> "pt"
            "it" -> "it"
            else -> "auto"
        }
    }

    /**
     * 把"旋转回正后坐标系"的 box 反推回原图坐标。
     *
     * 有道 ocrtransapi 在检测到图片需要旋转才正向时（orientation=Right/Left/Down），
     * 会先在内部把图旋转好再做 OCR，返回的 boundingBox 基于"旋转后"坐标，跟我们送出的
     * 原图（如 1440×1894 竖屏）对不上——典型现象：box.right 超过 srcW，渲染时叠加到屏幕外。
     *
     * 经验对应（实测 1440×1894 竖屏漫画，box #1 right=1848 反推后右下角对话气泡位置吻合）：
     *  - Right = 原图需逆时针 90° 才正向，即图在"已被顺转 90°"状态 →
     *      (xr, yr) → 原(srcW-1-yr, xr)，box (l,t,r,b) → (srcW-b, l, srcW-t, r)
     *  - Left  = 原图需顺时针 90° 才正向 → (xr, yr) → (yr, srcH-1-xr)，box → (t, srcH-r, b, srcH-l)
     *  - Down  = 180° → (srcW-r, srcH-b, srcW-l, srcH-t)
     *  - Up / null / 其它 = 不变
     */
    private fun applyOrientation(box: Rect, orientation: String?, srcW: Int, srcH: Int): Rect {
        return when (orientation?.lowercase()) {
            "right" -> Rect(srcW - box.bottom, box.left, srcW - box.top, box.right)
            "left" -> Rect(box.top, srcH - box.right, box.bottom, srcH - box.left)
            "down" -> Rect(srcW - box.right, srcH - box.bottom, srcW - box.left, srcH - box.top)
            else -> box
        }
    }

    private fun parseXywhBox(bb: String?): Rect {
        val parts = bb?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return Rect(0, 0, 0, 0)
        if (parts.size >= 4) {
            return Rect(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
        }
        return Rect(0, 0, 0, 0)
    }

    @Serializable
    private data class PicTransResp(
        val errorCode: String? = null,
        val lanFrom: String? = null,
        val lanTo: String? = null,
        val orientation: String? = null,
        val resRegions: List<RegionBody>? = null
    )

    @Serializable
    private data class RegionBody(
        val boundingBox: String? = null,
        val context: String? = null,
        @SerialName("tranContent") val tranContent: String? = null,
        val linesCount: Int? = null,
        val lineheight: Int? = null,
        val linespace: Int? = null
    )
}
