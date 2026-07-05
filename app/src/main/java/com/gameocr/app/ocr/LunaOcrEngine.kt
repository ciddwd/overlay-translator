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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
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

internal fun lunaOcrEndpointUrlOrNull(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return null
    val endpoint = if (trimmed.endsWith("/api/ocr")) trimmed else "$trimmed/api/ocr"
    return endpoint.takeIf { it.toHttpUrlOrNull() != null }
}

internal fun lunaOcrHttpHostOrNull(raw: String): String? {
    val url = lunaOcrEndpointUrlOrNull(raw)?.toHttpUrlOrNull() ?: return null
    return url.host.takeIf { url.scheme.equals("http", ignoreCase = true) }
}

internal data class LunaOcrParsedResponse(
    val engineId: String?,
    val engineName: String?,
    val timeCostSeconds: Double?,
    val vertical: Boolean,
    val blocks: List<LunaOcrParsedBlock>,
)

internal data class LunaOcrParsedBlock(
    val text: String,
    val boundingBox: LunaOcrBounds?,
)

internal data class LunaOcrBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

internal fun parseLunaOcrResponse(raw: String, json: Json = Json { ignoreUnknownKeys = true }): LunaOcrParsedResponse {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
        throw RuntimeException("Luna-OCR parse failed: ${raw.take(200)}")
    }
    val error = root["error"].asStringOrNull()
    if (!error.isNullOrBlank()) {
        throw RuntimeException("Luna-OCR error: ${error.take(200)}")
    }

    val results = (root["results"] as? JsonArray)
        ?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = obj["text"].asStringOrNull().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            LunaOcrParsedBlock(
                text = text,
                boundingBox = lunaBoundsFromBox(obj["box"]),
            )
        }
        .orEmpty()

    val fallbackText = root["text"].asStringOrNull()
    val blocks = if (results.isEmpty() && !fallbackText.isNullOrBlank()) {
        listOf(LunaOcrParsedBlock(fallbackText, null))
    } else {
        results
    }

    val engine = runCatching { root["engine"]?.jsonObject }.getOrNull()
    return LunaOcrParsedResponse(
        engineId = engine?.get("id").asStringOrNull(),
        engineName = engine?.get("name").asStringOrNull(),
        timeCostSeconds = root["timecost"]?.jsonPrimitive?.doubleOrNull,
        vertical = root["vertical"]?.jsonPrimitive?.booleanOrNull == true,
        blocks = blocks,
    )
}

internal fun lunaBoundsFromBox(box: JsonElement?): LunaOcrBounds? {
    val points = runCatching { box?.jsonArray }.getOrNull().orEmpty()
    val xs = mutableListOf<Int>()
    val ys = mutableListOf<Int>()
    points.forEach { point ->
        val obj = runCatching { point.jsonObject }.getOrNull() ?: return@forEach
        val x = obj["x"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
        val y = obj["y"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
        if (x != null && y != null) {
            xs += x
            ys += y
        }
    }
    if (xs.isEmpty() || ys.isEmpty()) return null
    return LunaOcrBounds(xs.min(), ys.min(), xs.max(), ys.max())
}

private fun JsonElement?.asStringOrNull(): String? =
    runCatching { this?.jsonPrimitive?.content }.getOrNull()

@Singleton
class LunaOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val settings = settingsRepository.get()
        val endpoint = lunaOcrEndpointUrlOrNull(settings.lunaOcrBaseUrl)
            ?: throw IllegalStateException(appContext.getString(R.string.err_luna_ocr_no_url))
        val encoded = withContext(Dispatchers.Default) { encodePng(bitmap) }
        val requestBody = buildJsonObject {
            put("image", encoded.base64)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        Timber.i(
            "LunaOCR request url=%s image=%dx%d pngBytes=%d base64Chars=%d timeout=%ds",
            endpoint,
            bitmap.width,
            bitmap.height,
            encoded.pngBytes,
            encoded.base64.length,
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
                    throw RuntimeException("Luna-OCR HTTP ${response.code}: ${body.take(200)}")
                }
                body
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val parsed = parseLunaOcrResponse(raw, json)
        Timber.i(
            "LunaOCR response blocks=%d vertical=%s engine=%s/%s serviceTime=%s elapsed=%dms",
            parsed.blocks.size,
            parsed.vertical,
            parsed.engineId.orEmpty(),
            parsed.engineName.orEmpty(),
            parsed.timeCostSeconds?.let { "%.3fs".format(it) } ?: "null",
            elapsedMs,
        )

        val fallbackBox = Rect(0, 0, bitmap.width, bitmap.height)
        val orientation = if (parsed.vertical) TextOrientation.VERTICAL_RTL else null
        return parsed.blocks.mapIndexed { index, block ->
            val box = block.boundingBox?.toRect() ?: fallbackBox
            Timber.i(
                "LunaOCR result[%d] box=(%d,%d,%d,%d %dx%d) textStats=%s text='%s'",
                index,
                box.left,
                box.top,
                box.right,
                box.bottom,
                box.width(),
                box.height(),
                paddleLogTextStats(block.text).toLogString(),
                block.text.forPaddleOcrLog(),
            )
            TextBlock(
                text = block.text,
                boundingBox = box,
                confidence = 1f,
                recognizedLanguage = settings.sourceLang.takeIf { it != "auto" } ?: "auto",
                layoutOrientation = orientation,
            )
        }
    }

    override fun close() {
        // Shared OkHttp client; no local resources to release.
    }

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
