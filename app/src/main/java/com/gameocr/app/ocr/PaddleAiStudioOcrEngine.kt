package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

internal const val PADDLE_AI_STUDIO_JOBS_URL = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs"
internal const val PADDLE_AI_STUDIO_MODEL = "PP-OCRv6"

private const val PADDLE_AI_STUDIO_POLL_INTERVAL_MS = 2_000L
private const val PADDLE_AI_STUDIO_MAX_JPEG_BYTES = 45_000_000

@Singleton
class PaddleAiStudioOcrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository
) : OcrEngine {

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val settings = settingsRepository.get()
        val token = settings.paddleAiStudioToken.trim()
        if (token.isBlank()) {
            throw IllegalStateException(appContext.getString(R.string.err_paddle_ai_studio_no_token))
        }

        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val jpeg = withContext(Dispatchers.Default) { encodeJpeg(bitmap) }
        Timber.i(
            "PaddleAiStudioOCR submit model=%s image=%dx%d jpegBytes=%d timeout=%ds",
            PADDLE_AI_STUDIO_MODEL,
            bitmap.width,
            bitmap.height,
            jpeg.size,
            settings.apiTimeoutSeconds
        )

        val jobId = submitJob(token, jpeg, timedClient)
        val resultUrl = pollResultUrl(token, jobId, timedClient, settings.apiTimeoutSeconds)
        val jsonl = downloadResultJsonl(resultUrl, timedClient)
        val blocks = parsePaddleAiStudioOcrBlocks(jsonl, bitmap.width, json)
        Timber.i("PaddleAiStudioOCR done jobId=%s blocks=%d", jobId, blocks.size)
        return blocks
    }

    override fun close() {
        // Shared OkHttpClient is owned by DI.
    }

    private suspend fun submitJob(token: String, jpeg: ByteArray, timedClient: OkHttpClient): String =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", PADDLE_AI_STUDIO_MODEL)
                .addFormDataPart("optionalPayload", optionalPayloadJson())
                .addFormDataPart(
                    "file",
                    "gameocr.jpg",
                    jpeg.toRequestBody("image/jpeg".toMediaType())
                )
                .build()
            val req = Request.Builder()
                .url(PADDLE_AI_STUDIO_JOBS_URL)
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            timedClient.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("PaddleOCR AI Studio submit HTTP ${response.code}: ${raw.take(200)}")
                }
                parsePaddleAiStudioSubmitResponse(raw, json)
            }
        }

    private suspend fun pollResultUrl(
        token: String,
        jobId: String,
        timedClient: OkHttpClient,
        timeoutSeconds: Int
    ): String {
        val deadline = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(5) * 1000L
        while (System.currentTimeMillis() <= deadline) {
            val state = fetchJobState(token, jobId, timedClient)
            when (state.status) {
                PaddleAiStudioJobStatus.DONE -> return requireNotNull(state.jsonUrl) {
                    "PaddleOCR AI Studio job done without jsonUrl: $jobId"
                }
                PaddleAiStudioJobStatus.FAILED ->
                    throw RuntimeException("PaddleOCR AI Studio job failed: ${state.errorMessage.orEmpty()}")
                PaddleAiStudioJobStatus.PENDING,
                PaddleAiStudioJobStatus.RUNNING -> delay(PADDLE_AI_STUDIO_POLL_INTERVAL_MS)
            }
        }
        throw RuntimeException("PaddleOCR AI Studio job timeout after ${timeoutSeconds}s: $jobId")
    }

    private suspend fun fetchJobState(
        token: String,
        jobId: String,
        timedClient: OkHttpClient
    ): PaddleAiStudioJobState = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(paddleAiStudioJobResultUrl(jobId))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        timedClient.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw RuntimeException("PaddleOCR AI Studio poll HTTP ${response.code}: ${raw.take(200)}")
            }
            parsePaddleAiStudioJobResponse(raw, json)
        }
    }

    private suspend fun downloadResultJsonl(url: String, timedClient: OkHttpClient): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).get().build()
            timedClient.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("PaddleOCR AI Studio result HTTP ${response.code}: ${raw.take(200)}")
                }
                raw
            }
        }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= PADDLE_AI_STUDIO_MAX_JPEG_BYTES || quality <= 35) {
                return bytes
            }
            quality -= 10
        }
    }

    private fun optionalPayloadJson(): String =
        """{"useDocOrientationClassify":false,"useDocUnwarping":false,"useTextlineOrientation":false}"""
}

internal enum class PaddleAiStudioJobStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}

internal data class PaddleAiStudioJobState(
    val status: PaddleAiStudioJobStatus,
    val jsonUrl: String? = null,
    val errorMessage: String? = null
)

internal fun paddleAiStudioJobResultUrl(jobId: String): String =
    "$PADDLE_AI_STUDIO_JOBS_URL/${jobId.trim()}"

internal fun parsePaddleAiStudioSubmitResponse(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true }
): String {
    val root = parseJsonObject(raw, "submit")
    ensurePaddleAiStudioCodeSuccess(root, raw)
    val jobId = root.objectAt("data")?.stringAt("jobId") ?: root.stringAt("jobId")
    return jobId?.takeIf { it.isNotBlank() }
        ?: throw RuntimeException("PaddleOCR AI Studio submit missing jobId: ${raw.take(200)}")
}

internal fun parsePaddleAiStudioJobResponse(
    raw: String,
    json: Json = Json { ignoreUnknownKeys = true }
): PaddleAiStudioJobState {
    val root = parseJsonObject(raw, "job")
    ensurePaddleAiStudioCodeSuccess(root, raw)
    val data = root.objectAt("data") ?: root
    return when (val state = data.stringAt("state")?.lowercase()) {
        "pending" -> PaddleAiStudioJobState(PaddleAiStudioJobStatus.PENDING)
        "running" -> PaddleAiStudioJobState(PaddleAiStudioJobStatus.RUNNING)
        "done" -> {
            val jsonUrl = data.objectAt("resultUrl")?.stringAt("jsonUrl") ?: data.stringAt("jsonUrl")
            PaddleAiStudioJobState(PaddleAiStudioJobStatus.DONE, jsonUrl = jsonUrl)
        }
        "failed" -> PaddleAiStudioJobState(
            PaddleAiStudioJobStatus.FAILED,
            errorMessage = data.stringAt("errorMsg") ?: root.stringAt("msg")
        )
        else -> throw RuntimeException("PaddleOCR AI Studio unknown job state '$state': ${raw.take(200)}")
    }
}

internal fun parsePaddleAiStudioOcrBlocks(
    raw: String,
    imageWidth: Int,
    json: Json = Json { ignoreUnknownKeys = true }
): List<TextBlock> {
    val parsedLines = parsePaddleAiStudioResultLines(raw, json)
    val fallbackWidth = imageWidth.coerceAtLeast(1)
    return parsedLines.mapIndexed { index, line ->
        TextBlock(
            text = line.text,
            boundingBox = line.rect ?: Rect(0, 80 + index * 60, fallbackWidth, 80 + (index + 1) * 60),
            confidence = line.confidence,
            recognizedLanguage = "auto"
        )
    }
}

private data class PaddleAiStudioParsedLine(
    val text: String,
    val rect: Rect?,
    val confidence: Float
)

private fun parsePaddleAiStudioResultLines(raw: String, json: Json): List<PaddleAiStudioParsedLine> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    val whole = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    val elements = if (whole != null) {
        listOf(whole)
    } else {
        trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                runCatching { json.parseToJsonElement(line) }
                    .getOrElse { throw RuntimeException("PaddleOCR AI Studio result parse failed: ${line.take(200)}", it) }
            }
            .toList()
    }
    return elements.flatMap { element ->
        when (element) {
            is JsonArray -> element.flatMap { parseResultElement(it) }
            else -> parseResultElement(element)
        }
    }
}

private fun parseResultElement(element: JsonElement): List<PaddleAiStudioParsedLine> =
    findOcrResultItems(element).flatMap { item -> parseOcrResultItem(item) }

private fun findOcrResultItems(element: JsonElement?): List<JsonElement> {
    val obj = element as? JsonObject ?: return emptyList()
    when (val direct = obj["ocrResults"]) {
        is JsonArray -> return direct.toList()
        is JsonObject -> return listOf(direct)
        else -> Unit
    }
    if (obj["prunedResult"] != null || obj["rec_texts"] != null || obj["text"] != null) {
        return listOf(obj)
    }
    listOf("result", "data", "results").forEach { key ->
        val nested = obj[key]
        val found = when (nested) {
            is JsonArray -> nested.flatMap { findOcrResultItems(it) }
            else -> findOcrResultItems(nested)
        }
        if (found.isNotEmpty()) return found
    }
    return emptyList()
}

private fun parseOcrResultItem(element: JsonElement): List<PaddleAiStudioParsedLine> {
    val obj = element as? JsonObject ?: return emptyList()
    val pruned = obj.objectAt("prunedResult") ?: obj
    val texts = pruned.stringListAt("rec_texts", "texts", "words")
        ?: pruned.stringAt("rec_text", "text", "words")?.let(::listOf)
        ?: obj.stringAt("text", "words")?.let(::listOf)
        ?: return emptyList()
    val scores = pruned.numberListAt("rec_scores", "scores", "confidences").orEmpty()
    val boxes = pruned.arrayAt("rec_boxes", "rec_polys", "dt_polys", "polys", "boxes")
    return texts.mapIndexedNotNull { index, text ->
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return@mapIndexedNotNull null
        PaddleAiStudioParsedLine(
            text = cleanText,
            rect = boxes?.getOrNull(index)?.toRectOrNull(),
            confidence = scores.getOrNull(index)?.toFloat() ?: 1f
        )
    }
}

private fun parseJsonObject(raw: String, label: String): JsonObject =
    runCatching { Json.parseToJsonElement(raw).asObjectOrNull() }.getOrNull()
        ?: throw RuntimeException("PaddleOCR AI Studio $label parse failed: ${raw.take(200)}")

private fun ensurePaddleAiStudioCodeSuccess(root: JsonObject, raw: String) {
    val code = root.intAt("code") ?: root.intAt("errorCode")
    if (code != null && code != 0) {
        val message = root.stringAt("msg", "errorMsg").orEmpty()
        throw RuntimeException("PaddleOCR AI Studio code=$code: ${message.ifBlank { raw.take(200) }}")
    }
}

private fun JsonObject.objectAt(name: String): JsonObject? =
    this[name].asObjectOrNull()

private fun JsonObject.stringAt(vararg names: String): String? =
    names.asSequence()
        .mapNotNull { name -> this[name].asStringOrNull() }
        .firstOrNull()

private fun JsonObject.intAt(name: String): Int? =
    this[name].asStringOrNull()?.toIntOrNull()

private fun JsonObject.stringListAt(vararg names: String): List<String>? =
    names.asSequence()
        .mapNotNull { name ->
            when (val value = this[name]) {
                is JsonArray -> value.mapNotNull { it.asStringOrNull() }
                is JsonPrimitive -> value.contentOrNull?.let(::listOf)
                else -> null
            }
        }
        .firstOrNull()

private fun JsonObject.numberListAt(vararg names: String): List<Double>? =
    names.asSequence()
        .mapNotNull { name ->
            when (val value = this[name]) {
                is JsonArray -> value.mapNotNull { it.asStringOrNull()?.toDoubleOrNull() }
                is JsonPrimitive -> value.contentOrNull?.toDoubleOrNull()?.let(::listOf)
                else -> null
            }
        }
        .firstOrNull()

private fun JsonObject.arrayAt(vararg names: String): JsonArray? =
    names.asSequence()
        .mapNotNull { name -> this[name] as? JsonArray }
        .firstOrNull()

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement.toRectOrNull(): Rect? {
    val nums = flattenNumbers(this)
    if (nums.size >= 8) {
        val xs = nums.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = nums.filterIndexed { index, _ -> index % 2 == 1 }
        return rectFromBounds(xs.minOrNull(), ys.minOrNull(), xs.maxOrNull(), ys.maxOrNull())
    }
    if (nums.size >= 4) {
        return rectFromBounds(nums[0], nums[1], nums[2], nums[3])
    }
    return null
}

private fun flattenNumbers(element: JsonElement): List<Double> = when (element) {
    is JsonArray -> element.flatMap { flattenNumbers(it) }
    is JsonPrimitive -> element.contentOrNull?.toDoubleOrNull()?.let(::listOf).orEmpty()
    else -> emptyList()
}

private fun rectFromBounds(left: Double?, top: Double?, right: Double?, bottom: Double?): Rect? {
    if (left == null || top == null || right == null || bottom == null) return null
    val l = minOf(left, right).roundToInt()
    val t = minOf(top, bottom).roundToInt()
    val r = maxOf(left, right).roundToInt()
    val b = maxOf(top, bottom).roundToInt()
    return Rect(l, t, r, b).takeIf { r > l && b > t }
}
