package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.R
import com.gameocr.app.data.BaiduOcrEndpoint
import com.gameocr.app.data.BaiduOcrLanguage
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream

internal fun baiduLanguageTypeFor(
    endpoint: BaiduOcrEndpoint,
    configuredLanguage: BaiduOcrLanguage,
): String? = when {
    endpoint == BaiduOcrEndpoint.WEBIMAGE -> null
    configuredLanguage.supportedOn(endpoint) -> configuredLanguage.code
    else -> BaiduOcrLanguage.CHN_ENG.code
}

/**
 * 百度通用文字识别（云端兜底）。
 *
 * 端点：
 * - OAuth：POST https://aip.baidubce.com/oauth/2.0/token  (client_credentials)
 * - 通用识别：POST https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=xxx
 *   body x-www-form-urlencoded: image=<base64 jpg>
 *
 * 复杂背景下识别率比 ML Kit on-device 稳，限频 2 QPS（免费档），按需切换。
 */
@Singleton
class BaiduOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val settings = settingsRepository.get()
        if (settings.baiduOcrApiKey.isBlank() || settings.baiduOcrSecretKey.isBlank()) {
            throw IllegalStateException(appContext.getString(R.string.err_baidu_no_keys))
        }
        val endpoint = settings.baiduOcrEndpoint
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val token = obtainToken(settings.baiduOcrApiKey, settings.baiduOcrSecretKey, timedClient)
        val encoded = withContext(Dispatchers.Default) { encodeJpeg(bitmap) }
        val languageType = baiduLanguageTypeFor(endpoint, settings.baiduOcrLanguage)
        Timber.i(
            "BaiduOCR request endpoint=%s hasLocation=%s configuredLang=%s languageType=%s image=%dx%d encoded=%dx%d jpegBytes=%d base64Chars=%d quality=%d timeout=%ds",
            endpoint.name,
            endpoint.hasLocation,
            settings.baiduOcrLanguage.name,
            languageType ?: "none",
            bitmap.width,
            bitmap.height,
            encoded.width,
            encoded.height,
            encoded.jpegBytes,
            encoded.base64.length,
            encoded.quality,
            settings.apiTimeoutSeconds,
        )

        val url = "https://aip.baidubce.com/rest/2.0/ocr/v1/${endpoint.path}".toHttpUrl()
            .newBuilder().addQueryParameter("access_token", token).build()
        // language_type 在不同端点支持范围不同：
        //  - accurate_basic / accurate（高精度系）：全 25 种含 auto_detect
        //  - general_basic / general（标准系）：10 种主流（CHN_ENG/ENG/JAP/KOR/FRE/SPA/POR/GER/ITA/RUS）
        //  - webimage：不读 language_type
        // 兜底：用户选的语种若在当前 endpoint 不支持，降级到 CHN_ENG 避免 216200 error。
        val form = FormBody.Builder()
            .add("image", encoded.base64)
            .apply {
                if (settings.baiduOcrLanguage.supportedOn(endpoint)) {
                    add("language_type", settings.baiduOcrLanguage.code)
                } else if (endpoint != com.gameocr.app.data.BaiduOcrEndpoint.WEBIMAGE) {
                    // 该端点本来支持 language_type，只是用户选的具体语种不在子集里 → 兜底
                    add("language_type", com.gameocr.app.data.BaiduOcrLanguage.CHN_ENG.code)
                }
            }
            .build()
        val req = Request.Builder().url(url).post(form).build()

        val requestStart = System.currentTimeMillis()
        val resp = withContext(Dispatchers.IO) {
            timedClient.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RuntimeException("Baidu OCR HTTP ${r.code}: ${raw.take(200)}")
                json.decodeFromString<BaiduOcrResponse>(raw)
            }
        }
        val elapsedMs = System.currentTimeMillis() - requestStart
        if (resp.errorCode != null && resp.errorCode != 0) {
            Timber.w(
                "BaiduOCR error endpoint=%s logId=%s elapsed=%dms code=%s message=%s",
                endpoint.name,
                resp.logId?.toString() ?: "null",
                elapsedMs,
                resp.errorCode,
                resp.errorMsg.orEmpty().forPaddleOcrLog(),
            )
            throw RuntimeException("Baidu OCR err ${resp.errorCode} (${endpoint.name}): ${resp.errorMsg.orEmpty()}")
        }
        val words = resp.wordsResult.orEmpty()
        Timber.i(
            "BaiduOCR response endpoint=%s logId=%s declaredNum=%s actual=%d elapsed=%dms",
            endpoint.name,
            resp.logId?.toString() ?: "null",
            resp.wordsResultNum?.toString() ?: "null",
            words.size,
            elapsedMs,
        )
        return words.mapIndexed { i, w ->
            // 含位置版用真实 boundingBox；无位置版退化为垂直排列的伪 Rect 让叠加层能逐条显示
            val box = w.location?.let { loc ->
                val left = loc.left ?: 0
                val top = loc.top ?: 0
                val width = loc.width ?: 0
                val height = loc.height ?: 0
                Rect(left, top, left + width, top + height)
            } ?: Rect(0, 80 + i * 60, bitmap.width, 80 + (i + 1) * 60)
            val text = w.words.orEmpty()
            Timber.i(
                "BaiduOCR result[%d] box=(%d,%d,%d,%d %dx%d) hasLocation=%s textStats=%s text='%s'",
                i,
                box.left,
                box.top,
                box.right,
                box.bottom,
                box.width(),
                box.height(),
                w.location != null,
                paddleLogTextStats(text).toLogString(),
                text.forPaddleOcrLog(),
            )
            TextBlock(
                text = text,
                boundingBox = box,
                confidence = 1f,
                recognizedLanguage = "auto"
            )
        }
    }

    private suspend fun obtainToken(apiKey: String, secret: String, httpClient: OkHttpClient): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiresAt - 60_000 }?.let { return@withLock it }

        val url = "https://aip.baidubce.com/oauth/2.0/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "client_credentials")
            .addQueryParameter("client_id", apiKey)
            .addQueryParameter("client_secret", secret)
            .build()
        val req = Request.Builder().url(url).post(FormBody.Builder().build()).build()
        val token = withContext(Dispatchers.IO) {
            httpClient.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw RuntimeException("Baidu token HTTP ${r.code}: ${raw.take(200)}")
                val parsed = json.decodeFromString<BaiduTokenResponse>(raw)
                cachedToken = parsed.accessToken
                tokenExpiresAt = now + parsed.expiresIn * 1000L
                parsed.accessToken
            }
        }
        Timber.i("Baidu token refreshed, ttl=${(tokenExpiresAt - now) / 1000}s")
        token
    }

    private fun encodeJpeg(bitmap: Bitmap): EncodedJpeg {
        // 百度限制（错误码 216202 image size error）：
        // - 最长边 ≤ 4096px / 最短边 ≥ 15px
        // - base64 后 < 4MB
        // 用户若开启预处理 2x 上采样，截屏会被放大，容易撞这两条。这里在送出前先做安全缩放：
        // 1) 等比缩到最长边 ≤ 4096
        // 2) 循环降低 JPEG 质量直到 base64 ≤ ~3.5MB（留 buffer 给 form 编码开销）
        // 宽高比 1:4 / 4:1 超出的情况只能让用户调整截屏区（这里无法救）。
        val maxDim = 4096
        val maxBase64Bytes = 3_500_000

        var bmp = bitmap
        var recycleBmp = false
        val longestSide = maxOf(bmp.width, bmp.height)
        if (longestSide > maxDim) {
            val scale = maxDim.toDouble() / longestSide
            val newW = (bmp.width * scale).toInt().coerceAtLeast(15)
            val newH = (bmp.height * scale).toInt().coerceAtLeast(15)
            bmp = Bitmap.createScaledBitmap(bmp, newW, newH, true)
            recycleBmp = true
        }

        var quality = 85
        try {
            while (true) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val jpeg = out.toByteArray()
                val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                if (b64.length <= maxBase64Bytes || quality <= 30) {
                    return EncodedJpeg(
                        base64 = b64,
                        width = bmp.width,
                        height = bmp.height,
                        quality = quality,
                        jpegBytes = jpeg.size,
                    )
                }
                quality -= 15
            }
        } finally {
            if (recycleBmp) bmp.recycle()
        }
    }

    override fun close() { /* OkHttp 共享，不在这里关 */ }

    private data class EncodedJpeg(
        val base64: String,
        val width: Int,
        val height: Int,
        val quality: Int,
        val jpegBytes: Int,
    )

    @Serializable
    private data class BaiduTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long
    )

    @Serializable
    private data class BaiduOcrResponse(
        @SerialName("words_result") val wordsResult: List<BaiduWords>? = null,
        @SerialName("words_result_num") val wordsResultNum: Int? = null,
        @SerialName("log_id") val logId: Long? = null,
        @SerialName("error_code") val errorCode: Int? = null,
        @SerialName("error_msg") val errorMsg: String? = null
    )

    @Serializable
    private data class BaiduLocation(
        val top: Int? = null,
        val left: Int? = null,
        val width: Int? = null,
        val height: Int? = null
    )

    @Serializable
    private data class BaiduWords(
        val words: String? = null,
        val location: BaiduLocation? = null
    )
}
