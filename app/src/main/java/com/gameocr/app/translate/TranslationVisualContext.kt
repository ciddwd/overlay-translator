package com.gameocr.app.translate

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.RuntimeTranslationVisualContext
import com.gameocr.app.data.RuntimeVisualTextItem
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.ocr.TextBlock
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

internal object TranslationVisualContextPolicy {
    fun supportsEngine(engine: TranslatorEngine): Boolean =
        engine == TranslatorEngine.OPENAI || engine == TranslatorEngine.ANTHROPIC

    fun shouldPrepare(settings: Settings, presentation: RenderMode = settings.renderMode): Boolean {
        if (!settings.openAiRequestOptions.sendScreenImage) return false
        if (!supportsEngine(settings.translatorEngine)) return false
        val floatingMergeAll = presentation == RenderMode.FLOATING_WINDOW &&
            settings.mergeAdjacentBlocks &&
            settings.mergeStrength == MergeStrength.ALL
        return floatingMergeAll || settings.translationContextMode != TranslationContextMode.FAST_PER_SEGMENT
    }
}

internal object VisualRequestFallbackPolicy {
    fun shouldRetryWithoutImage(httpCode: Int): Boolean =
        httpCode == 400 || httpCode == 413 || httpCode == 415 || httpCode == 422
}

internal object VisualResponseFallbackPolicy {
    fun shouldRetryWithoutImage(
        visualContextPresent: Boolean,
        fallbackAllowed: Boolean,
        structuredResponseComplete: Boolean,
    ): Boolean = visualContextPresent && fallbackAllowed && !structuredResponseComplete
}

internal class VisualContextRejectedException(message: String) : RuntimeException(message)

internal object RuntimeTranslationVisualContextFactory {
    private const val MAX_LONG_EDGE = 1536
    private const val JPEG_QUALITY = 88
    private const val COORDINATE_SCALE = 1000

    fun create(
        bitmap: Bitmap,
        blocks: List<TextBlock>,
        presentation: RenderMode,
        combineIntoSingleOutput: Boolean,
    ): RuntimeTranslationVisualContext? {
        if (bitmap.width <= 0 || bitmap.height <= 0 || blocks.isEmpty()) return null
        val encoded = encode(bitmap) ?: return null
        val items = blocks.mapIndexedNotNull { index, block ->
            val source = PageTranslationPresentationTextPolicy.normalize(
                presentation,
                block.text,
            ).takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            block.boundingBox.toNormalizedVisualItem(
                id = index + 1,
                source = source,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
        }
        if (items.isEmpty()) return null
        return RuntimeTranslationVisualContext(
            mimeType = "image/jpeg",
            base64Data = Base64.encodeToString(encoded.bytes, Base64.NO_WRAP),
            width = encoded.width,
            height = encoded.height,
            byteCount = encoded.bytes.size,
            sha256 = encoded.bytes.sha256Hex(),
            items = items,
            combineIntoSingleOutput = combineIntoSingleOutput,
        )
    }

    private fun encode(bitmap: Bitmap): EncodedImage? {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        val scale = (MAX_LONG_EDGE.toFloat() / longEdge).coerceAtMost(1f)
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val resized = if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
                output.toByteArray()
            }
            EncodedImage(bytes, targetWidth, targetHeight)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun Rect.toNormalizedVisualItem(
        id: Int,
        source: String,
        imageWidth: Int,
        imageHeight: Int,
    ): RuntimeVisualTextItem = RuntimeVisualTextItem(
        id = id,
        source = source,
        left = normalize(left, imageWidth),
        top = normalize(top, imageHeight),
        right = normalize(right, imageWidth),
        bottom = normalize(bottom, imageHeight),
    )

    private fun normalize(value: Int, extent: Int): Int =
        ((value.coerceIn(0, extent).toLong() * COORDINATE_SCALE) / extent.coerceAtLeast(1))
            .toInt()
            .coerceIn(0, COORDINATE_SCALE)

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class EncodedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )
}

internal object VisualTranslationPromptPolicy {
    fun buildUserPayload(
        context: RuntimeTranslationVisualContext,
        activeIds: Set<Int>,
    ): String = buildString {
        if (context.combineIntoSingleOutput) {
            append("请结合画面判断以下文字的阅读顺序，并把全部内容翻译成一个连贯段落。")
            append("编号只用于定位文字。\n")
            append("只返回一个 JSON 对象，不要解释：")
            append("{\"translations\":[{\"id\":1,\"translation\":\"...\"}]}\n\n")
        } else {
            append("请结合画面翻译以下文字。")
            append("只翻译这些编号：")
            append(activeIds.sorted().joinToString(","))
            append("。每个编号必须且只能返回一次。\n")
            append("只返回一个 JSON 对象，不要解释：")
            append("{\"translations\":[{\"id\":1,\"translation\":\"...\"}]}\n\n")
        }
        context.items.forEach { item ->
            append('#').append(item.id)
            append(" box=")
            append(item.left).append(',').append(item.top).append(',')
            append(item.right).append(',').append(item.bottom).append('\n')
            append(item.source).append("\n\n")
        }
    }.trimEnd()
}
