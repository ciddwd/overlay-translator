package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

private const val UMI_DEFAULT_LIMIT_SIDE_LEN = 4320
private const val UMI_DEFAULT_TBPU_PARSER = "none"
private const val UMI_DEFAULT_DATA_FORMAT = "dict"

internal fun umiOcrEndpointUrlOrNull(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    val endpoint = if (trimmed.endsWith("/api/ocr")) trimmed else "$trimmed/api/ocr"
    return endpoint.takeIf { it.toHttpUrlOrNull() != null }
}

internal fun umiOcrHttpHostOrNull(raw: String): String? {
    val url = umiOcrEndpointUrlOrNull(raw)?.toHttpUrlOrNull() ?: return null
    return url.host.takeIf { url.scheme.equals("http", ignoreCase = true) }
}

internal fun umiOcrLanguageConfigFor(sourceLang: String): String? {
    val tag = sourceLang.trim().lowercase()
    if (tag.isBlank() || tag == "auto") return null
    return when {
        tag == "zh-tw" || tag == "zh-hant" || tag.startsWith("zh-hant") ->
            "models/config_chinese_cht.txt"
        tag == "zh" || tag == "zh-cn" || tag == "zh-hans" || tag.startsWith("zh-hans") ->
            "models/config_chinese.txt"
        tag.startsWith("ja") -> "models/config_japan.txt"
        tag.startsWith("ko") -> "models/config_korean.txt"
        tag.startsWith("en") -> "models/config_en.txt"
        tag.startsWith("ru") -> "models/config_cyrillic.txt"
        else -> null
    }
}

internal data class UmiOcrParsedResponse(
    val code: Int,
    val serviceTimeSeconds: Double?,
    val blocks: List<UmiOcrParsedBlock>,
    val message: String?,
)

internal data class UmiOcrParsedBlock(
    val text: String,
    val score: Float,
    val boundingBox: Rect,
    val end: String,
)

internal data class UmiOcrBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

internal fun parseUmiOcrResponse(raw: String, json: Json = Json { ignoreUnknownKeys = true }): UmiOcrParsedResponse {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
        throw RuntimeException("Umi-OCR parse failed: ${raw.take(200)}")
    }
    val code = root["code"]?.jsonPrimitive?.intOrNull
        ?: throw RuntimeException("Umi-OCR response missing code: ${raw.take(200)}")
    val serviceTime = root["time"]?.jsonPrimitive?.doubleOrNull
    val data = root["data"]
    if (code == 101) {
        return UmiOcrParsedResponse(
            code = code,
            serviceTimeSeconds = serviceTime,
            blocks = emptyList(),
            message = data.asStringOrNull(),
        )
    }
    if (code != 100) {
        throw RuntimeException("Umi-OCR error code=$code: ${data.asStringOrNull().orEmpty().take(200)}")
    }
    val array = data as? JsonArray
        ?: throw RuntimeException("Umi-OCR expected data array for dict response: ${raw.take(200)}")
    val blocks = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val text = obj["text"].asStringOrNull().orEmpty()
        val score = obj["score"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f
        UmiOcrParsedBlock(
            text = text,
            score = score.coerceIn(0f, 1f),
            boundingBox = umiRectFromBox(obj["box"]),
            end = obj["end"].asStringOrNull().orEmpty(),
        )
    }
    return UmiOcrParsedResponse(
        code = code,
        serviceTimeSeconds = serviceTime,
        blocks = blocks,
        message = null,
    )
}

internal fun umiBoundsFromBox(box: JsonElement?): UmiOcrBounds? {
    val points = runCatching { box?.jsonArray }.getOrNull().orEmpty()
    val xs = mutableListOf<Int>()
    val ys = mutableListOf<Int>()
    points.forEach { point ->
        val pair = runCatching { point.jsonArray }.getOrNull() ?: return@forEach
        val x = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.roundToInt()
        val y = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull?.roundToInt()
        if (x != null && y != null) {
            xs += x
            ys += y
        }
    }
    if (xs.isEmpty() || ys.isEmpty()) return null
    return UmiOcrBounds(xs.min(), ys.min(), xs.max(), ys.max())
}

internal fun umiRectFromBox(box: JsonElement?): Rect =
    umiBoundsFromBox(box)?.toRect() ?: Rect(0, 0, 0, 0)

private fun JsonElement?.asStringOrNull(): String? =
    runCatching { this?.jsonPrimitive?.content }.getOrNull()

@Singleton
class UmiOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val settings = settingsRepository.get()
        val endpoint = umiOcrEndpointUrlOrNull(settings.umiOcrBaseUrl)
            ?: throw IllegalStateException(appContext.getString(R.string.err_umi_ocr_no_url))
        val languageConfig = umiOcrLanguageConfigFor(settings.sourceLang)
        val encoded = withContext(Dispatchers.Default) { encodePng(bitmap) }
        val requestBody = buildRequestBody(encoded.base64, languageConfig)
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        Timber.i(
            "UmiOCR request url=%s sourceLang=%s languageConfig=%s image=%dx%d pngBytes=%d base64Chars=%d limitSideLen=%d parser=%s dataFormat=%s timeout=%ds",
            endpoint,
            settings.sourceLang,
            languageConfig ?: "<umi-default>",
            bitmap.width,
            bitmap.height,
            encoded.pngBytes,
            encoded.base64.length,
            UMI_DEFAULT_LIMIT_SIDE_LEN,
            UMI_DEFAULT_TBPU_PARSER,
            UMI_DEFAULT_DATA_FORMAT,
            settings.apiTimeoutSeconds,
        )

        val req = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()
        val startedAt = System.currentTimeMillis()
        val raw = withContext(Dispatchers.IO) {
            timedClient.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("Umi-OCR HTTP ${response.code}: ${body.take(200)}")
                }
                body
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val parsed = parseUmiOcrResponse(raw, json)
        Timber.i(
            "UmiOCR response code=%d blocks=%d serviceTime=%s elapsed=%dms message=%s",
            parsed.code,
            parsed.blocks.size,
            parsed.serviceTimeSeconds?.let { "%.3fs".format(it) } ?: "null",
            elapsedMs,
            parsed.message?.forPaddleOcrLog() ?: "",
        )
        return parsed.blocks.mapIndexed { index, block ->
            val box = block.boundingBox
            Timber.i(
                "UmiOCR result[%d] box=(%d,%d,%d,%d %dx%d) score=%.3f end='%s' textStats=%s text='%s'",
                index,
                box.left,
                box.top,
                box.right,
                box.bottom,
                box.width(),
                box.height(),
                block.score,
                block.end.replace("\n", "\\n").replace("\r", "\\r"),
                paddleLogTextStats(block.text).toLogString(),
                block.text.forPaddleOcrLog(),
            )
            TextBlock(
                text = block.text,
                boundingBox = box,
                confidence = block.score,
                recognizedLanguage = settings.sourceLang.takeIf { it != "auto" } ?: "auto",
            )
        }
    }

    override fun close() {
        // Shared OkHttp client; no local resources to release.
    }

    private fun buildRequestBody(base64: String, languageConfig: String?) = buildJsonObject {
        put("base64", base64)
        put("options", buildJsonObject {
            if (languageConfig != null) put("ocr.language", languageConfig)
            put("ocr.cls", false)
            put("ocr.limit_side_len", UMI_DEFAULT_LIMIT_SIDE_LEN)
            put("tbpu.parser", UMI_DEFAULT_TBPU_PARSER)
            put("data.format", UMI_DEFAULT_DATA_FORMAT)
        })
    }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun encodePng(bitmap: Bitmap): EncodedPng {
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return EncodedPng(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            pngBytes = bytes.size,
        )
    }

    private data class EncodedPng(
        val base64: String,
        val pngBytes: Int,
    )
}
