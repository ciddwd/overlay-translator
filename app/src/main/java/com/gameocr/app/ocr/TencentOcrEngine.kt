package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.R
import com.gameocr.app.data.LogRepository
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.TencentOcrEndpoint
import com.gameocr.app.data.TencentOcrLanguage
import com.gameocr.app.data.withApiTimeout
import com.gameocr.app.util.TencentSigner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * 腾讯云 OCR - 通用印刷体识别（GeneralBasicOCR）。
 *
 * 签名走 TC3-HMAC-SHA256 v3：
 *   StringToSign = "TC3-HMAC-SHA256\n${date}\n${credentialScope}\n${hashed(CanonicalRequest)}"
 *   CanonicalRequest = "${method}\n${path}\n${query}\n${headers}\n${signedHeaders}\n${hashed(payload)}"
 *
 * 端点：ocr.tencentcloudapi.com（不分 region，但 Region header 必传，默认 ap-guangzhou）。
 */
@Singleton
class TencentOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val logRepository: LogRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val s = settingsRepository.get()
        if (s.tencentSecretId.isBlank() || s.tencentSecretKey.isBlank()) {
            throw IllegalStateException(appContext.getString(R.string.err_tencent_no_keys))
        }
        val region = s.tencentRegion.ifBlank { "ap-guangzhou" }
        val imgB64 = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }
        val endpoint = s.tencentOcrEndpoint
        // 官方文档：
        //  - GeneralBasicOCR 支持 LanguageType（全 23 种）
        //  - GeneralAccurateOCR 不接受 LanguageType（要用 ConfigID="MulOCR" 切多语种，本工程未接入）
        //  - RecognizeAgent 是 LLM 增强，自动多语判断
        val languageCode: String? = if (s.tencentOcrLanguage.supportedOn(endpoint)) {
            s.tencentOcrLanguage.code
        } else {
            null
        }
        val payload = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("ImageBase64", imgB64)
            if (languageCode != null) put("LanguageType", languageCode)
        })

        val timedClient = client.withApiTimeout(s.apiTimeoutSeconds)
        val (resp, raw) = withContext(Dispatchers.IO) {
            doSignedCall(
                httpClient = timedClient,
                secretId = s.tencentSecretId,
                secretKey = s.tencentSecretKey,
                service = SERVICE,
                host = HOST,
                region = region,
                action = endpoint.action,
                version = VERSION,
                payload = payload
            )
        }
        if (resp.response?.error != null) {
            throw RuntimeException("Tencent OCR ${resp.response.error.code} (${endpoint.name}): ${resp.response.error.message}")
        }
        // RecognizeAgent 响应嵌套了一层：外层 Response 下又有 `Response` 数组，
        // TextDetections 在数组元素里。通用 OCR 不嵌套，直接在外层取。
        val items = if (endpoint == TencentOcrEndpoint.RECOGNIZE_AGENT) {
            resp.response?.agentResponse?.flatMap { it.textDetections.orEmpty() }.orEmpty()
        } else {
            resp.response?.textDetections.orEmpty()
        }
        if (endpoint == TencentOcrEndpoint.RECOGNIZE_AGENT && items.isEmpty()) {
            val short = raw.take(800)
            Timber.tag("TencentOcr").i("[RecognizeAgent] empty result. raw=%s", short)
            logRepository.info(LogRepository.Category.OCR, "[RecognizeAgent] empty. raw: $short")
        }
        val blocks = items.mapIndexed { i, item -> toTextBlock(item, i, bitmap.width) }
        // RecognizeAgent 会返回多粒度 box（段级 + 字级 + 重复），用它在 AdvancedInfo 里附带的
        // ParagNo（腾讯自己算的段落分组）合并，比我们做几何聚类准很多。
        if (endpoint == TencentOcrEndpoint.RECOGNIZE_AGENT && blocks.isNotEmpty()) {
            val paragNos = items.map { parseParagNo(it.advancedInfo) }
            val allHavePN = paragNos.all { it != null }
            if (allHavePN) {
                val merged = groupByParagraph(blocks.zip(paragNos.map { it!! }))
                Timber.tag("TencentOcr").i("[RecognizeAgent] paragraph merge: %d -> %d", blocks.size, merged.size)
                logRepository.info(
                    LogRepository.Category.OCR,
                    "[RecognizeAgent] 按 ParagNo 合并: ${blocks.size} -> ${merged.size} 段"
                )
                return merged
            }
        }
        return blocks
    }

    private fun toTextBlock(item: TextDetection, index: Int, fallbackW: Int): TextBlock {
        val poly = item.polygon
        val box = if (poly.isNullOrEmpty()) Rect(0, 80 + index * 60, fallbackW, 80 + (index + 1) * 60)
        else Rect(
            poly.minOf { it.x ?: 0 },
            poly.minOf { it.y ?: 0 },
            poly.maxOf { it.x ?: 0 },
            poly.maxOf { it.y ?: 0 }
        )
        return TextBlock(
            text = item.detectedText.orEmpty(),
            boundingBox = box,
            confidence = (item.confidence ?: 100).toFloat() / 100f,
            recognizedLanguage = item.language ?: "auto"
        )
    }

    /**
     * 按 ParagNo 分组合并。每段：
     *  1) 先去嵌套——按面积降序遍历，新 box 如果完全嵌在已保留 box 内（段级框包字级框）则丢掉
     *  2) 剩下的 box union 出整段 bbox
     *  3) 文本拼接：若整段 bbox h > w 当竖排，按 left desc 排（日文 right-to-left）；否则当横排
     */
    private fun groupByParagraph(blocksWithParag: List<Pair<TextBlock, Int>>): List<TextBlock> {
        return blocksWithParag.groupBy { it.second }
            .map { (_, group) -> unionParagraph(group.map { it.first }) }
            .sortedBy { it.boundingBox.top }
    }

    private fun unionParagraph(blocks: List<TextBlock>): TextBlock {
        if (blocks.size == 1) return blocks[0]
        val deduped = removeNestedBoxes(blocks)
        val union = deduped.map { it.boundingBox }.reduce { a, b ->
            Rect(minOf(a.left, b.left), minOf(a.top, b.top),
                 maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }
        val isVertical = union.height() > union.width()
        val sorted = if (isVertical) {
            // 竖排日文 right-to-left：先按 left desc，再按 top asc
            deduped.sortedWith(
                compareByDescending<TextBlock> { it.boundingBox.left }.thenBy { it.boundingBox.top }
            )
        } else {
            // 横排：先 top，再 left
            deduped.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        }
        val sep = if (isVertical) "" else " "  // 竖排单字直接连，横排单字之间留空格
        val text = sorted.joinToString(sep) { it.text }
        val avgConf = deduped.map { it.confidence }.average().toFloat()
        return TextBlock(
            text = text,
            boundingBox = union,
            confidence = avgConf,
            recognizedLanguage = deduped.firstOrNull()?.recognizedLanguage
        )
    }

    /** 去掉完全嵌在更大 box 内的小 box——段级 box 已经包含段内所有字，字级 box 是重复信息。 */
    private fun removeNestedBoxes(blocks: List<TextBlock>): List<TextBlock> {
        val sorted = blocks.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
        val kept = mutableListOf<TextBlock>()
        for (b in sorted) {
            val nested = kept.any { existing ->
                val e = existing.boundingBox; val r = b.boundingBox
                r.left >= e.left && r.right <= e.right && r.top >= e.top && r.bottom <= e.bottom
            }
            if (!nested) kept.add(b)
        }
        return kept
    }

    private val paragNoRegex = Regex("\"ParagNo\"\\s*:\\s*(\\d+)")
    private fun parseParagNo(adv: String?): Int? =
        adv?.let { paragNoRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun doSignedCall(
        httpClient: okhttp3.OkHttpClient,
        secretId: String,
        secretKey: String,
        service: String,
        host: String,
        region: String,
        action: String,
        version: String,
        payload: String
    ): Pair<TencentOcrResponse, String> {
        val headers = TencentSigner.signTc3(
            secretId = secretId,
            secretKey = secretKey,
            service = service,
            host = host,
            region = region,
            action = action,
            version = version,
            payload = payload
        )
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url("https://$host/").post(body)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()

        return httpClient.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("Tencent HTTP ${r.code}: ${raw.take(200)}")
            val parsed = runCatching { json.decodeFromString<TencentOcrResponse>(raw) }
                .getOrElse {
                    throw RuntimeException(
                        appContext.getString(R.string.err_tencent_parse_failed_format, raw.take(200)),
                        it
                    )
                }
            parsed to raw
        }
    }

    override fun close() { /* 共享 OkHttp，不释放 */ }

    companion object {
        private const val HOST = "ocr.tencentcloudapi.com"
        private const val SERVICE = "ocr"
        private const val VERSION = "2018-11-19"
    }

    @Serializable
    private data class TencentOcrResponse(@SerialName("Response") val response: ResponseBody? = null)

    @Serializable
    private data class ResponseBody(
        @SerialName("TextDetections") val textDetections: List<TextDetection>? = null,
        @SerialName("Language") val language: String? = null,
        @SerialName("RequestId") val requestId: String? = null,
        @SerialName("Error") val error: ErrorBody? = null,
        /**
         * RecognizeAgent 专用：响应外层 Response 下又嵌套了一层 `Response` 数组，
         * `TextDetections` 在数组元素里（不像通用 OCR 直接放在外层）。本字段兜住这层
         * 嵌套；通用 OCR 接口返回 null。
         */
        @SerialName("Response") val agentResponse: List<AgentResponseItem>? = null
    )

    @Serializable
    private data class AgentResponseItem(
        @SerialName("TextDetections") val textDetections: List<TextDetection>? = null,
        @SerialName("Answer") val answer: String? = null
    )

    @Serializable
    private data class ErrorBody(
        @SerialName("Code") val code: String? = null,
        @SerialName("Message") val message: String? = null
    )

    @Serializable
    private data class TextDetection(
        @SerialName("DetectedText") val detectedText: String? = null,
        @SerialName("Confidence") val confidence: Int? = null,
        @SerialName("Polygon") val polygon: List<Coord>? = null,
        @SerialName("AdvancedInfo") val advancedInfo: String? = null,
        @SerialName("ItemPolygon") val itemPolygon: Coord? = null,
        @SerialName("Language") val language: String? = null
    )

    @Serializable
    private data class Coord(@SerialName("X") val x: Int? = null, @SerialName("Y") val y: Int? = null)
}
