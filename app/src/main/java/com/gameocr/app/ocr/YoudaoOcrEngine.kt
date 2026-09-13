package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 有道智云通用文字识别（ocrapi）。
 *
 * 端点：https://openapi.youdao.com/ocrapi
 * 签名：sha256(appKey + truncate(img) + salt + curtime + appSecret)，signType=v3
 * 响应：Result.regions[].lines[]，每条 line 带 text + boundingBox（"x1,y1,x2,y2,x3,y3,x4,y4" 四角八值）。
 */
@Singleton
class YoudaoOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        return recognize(bitmap, kind, settingsRepository.get())
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        kind: OcrEngineKind,
        settings: com.gameocr.app.data.Settings,
    ): List<TextBlock> {
        val s = settings
        if (s.youdaoAppKey.isBlank() || s.youdaoAppSecret.isBlank()) {
            throw IllegalStateException("有道 AppKey/AppSecret 未配置")
        }
        val imgB64 = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        val salt = UUID.randomUUID().toString()
        val curtime = (System.currentTimeMillis() / 1000L).toString()
        val sign = youdaoV3Sign(s.youdaoAppKey, s.youdaoAppSecret, imgB64, salt, curtime)
        val langType = mapLangType(s.sourceLang)
        val form = FormBody.Builder()
            .add("q", imgB64)  // 部分文档 / SDK 用 `q`，新版 ocrapi 也接 `img`，两者一样
            .add("img", imgB64)
            .add("langType", langType)
            .add("detectType", "10012")
            .add("imageType", "1")
            .add("docType", "json")
            .add("appKey", s.youdaoAppKey)
            .add("salt", salt)
            .add("curtime", curtime)
            .add("sign", sign)
            .add("signType", "v3")
            .build()
        val req = Request.Builder()
            .url("https://openapi.youdao.com/ocrapi")
            .post(form).build()
        val timedClient = client.withApiTimeout(s.apiTimeoutSeconds)
        return withContext(Dispatchers.IO) {
            timedClient.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    throw RuntimeException("有道 OCR HTTP ${r.code}: ${raw.take(200)}")
                }
                val parsed = runCatching { json.decodeFromString<YoudaoOcrResp>(raw) }
                    .getOrElse { throw RuntimeException("有道 OCR 解析失败: ${raw.take(200)}", it) }
                if (parsed.errorCode != "0") {
                    throw RuntimeException("有道 OCR errorCode=${parsed.errorCode}: ${raw.take(200)}")
                }
                parsed.Result?.regions?.flatMap { region ->
                    region.lines.orEmpty().map { line ->
                        TextBlock(
                            text = line.text.orEmpty(),
                            boundingBox = parsePolygonBox(line.boundingBox),
                            confidence = 1f,
                            recognizedLanguage = region.lang ?: parsed.lanFrom ?: "auto"
                        )
                    }
                }.orEmpty()
            }
        }
    }

    override fun close() { /* 共享 OkHttp，不释放 */ }

    /** sourceLang → 有道 OCR langType。auto/zh-CHS/zh-CHT/en/ja/ko/fr/de/es/ru/pt/it。 */
    private fun mapLangType(s: String): String {
        val core = s.substringBefore('-').lowercase()
        return when (s.lowercase()) {
            "auto" -> "auto"
            "zh-cn", "zh" -> "zh-CHS"
            "zh-tw", "zh-hant" -> "zh-CHT"
            else -> when (core) {
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
    }

    @Serializable
    private data class YoudaoOcrResp(
        val errorCode: String? = null,
        @SerialName("Result") val Result: ResultBody? = null,
        val lanFrom: String? = null
    )

    @Serializable
    private data class ResultBody(
        val regions: List<RegionBody>? = null,
        val orientation: String? = null
    )

    @Serializable
    private data class RegionBody(
        val boundingBox: String? = null,
        val dir: String? = null,
        val lang: String? = null,
        val lines: List<LineBody>? = null
    )

    @Serializable
    private data class LineBody(
        val boundingBox: String? = null,
        val text: String? = null
    )

    companion object {
        /**
         * 把"x1,y1,x2,y2,x3,y3,x4,y4"四角坐标转成 axis-aligned Rect（取 min/max）。
         * 有道 OCR 返回的是 polygon 八值，竖排或斜体时四角不一定与坐标轴平行。
         */
        internal fun parsePolygonBox(bb: String?): Rect {
            val parts = bb?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return Rect(0, 0, 0, 0)
            if (parts.size >= 8) {
                val xs = listOf(parts[0], parts[2], parts[4], parts[6])
                val ys = listOf(parts[1], parts[3], parts[5], parts[7])
                return Rect(xs.min(), ys.min(), xs.max(), ys.max())
            }
            if (parts.size >= 4) {
                // 容错：x,y,w,h 退化格式
                return Rect(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
            }
            return Rect(0, 0, 0, 0)
        }

        /**
         * 有道 v3 签名：sha256(appKey + truncate(input) + salt + curtime + appSecret)。
         * truncate(q): len ≤ 20 → q；否则 q[:10] + len + q[-10:]。这样长 base64 也能避免拼超长 sign 串。
         */
        internal fun youdaoV3Sign(
            appKey: String,
            appSecret: String,
            input: String,
            salt: String,
            curtime: String
        ): String {
            val truncated = if (input.length <= 20) input
            else input.substring(0, 10) + input.length + input.substring(input.length - 10)
            val signStr = appKey + truncated + salt + curtime + appSecret
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(signStr.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
