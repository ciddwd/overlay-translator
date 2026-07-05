package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.OcrEngineKind
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit on-device Text Recognition v2。latin / 日 / 中 / 韩四个识别器懒加载。
 *
 * AUTO 模式：依次尝试韩 → 日 → 拉丁 → 中。
 *  - 韩文识别器命中 Hangul 音节（U+AC00–U+D7AF）→ 用韩文
 *  - 日文识别器命中假名（平假名 / 片假名）→ 用日文
 *  - 拉丁识别器返回非空 → 用拉丁（覆盖英 / 数字 / 拉丁系）
 *  - 都没命中 → 中文兜底
 *
 * 韩文优先于日文是因为 Hangul 与假名形态截然不同、误检率几乎为零；放最前能避免韩漫被误判到日文 OCR。
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japanese: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val korean: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val input = InputImage.fromBitmap(bitmap, 0)
        return when (kind) {
            OcrEngineKind.ML_KIT_LATIN -> runRecognizer(latin, input, "latin")
            OcrEngineKind.ML_KIT_JAPANESE -> runRecognizer(japanese, input, "ja")
            OcrEngineKind.ML_KIT_CHINESE -> runRecognizer(chinese, input, "zh")
            OcrEngineKind.ML_KIT_KOREAN -> runRecognizer(korean, input, "ko")
            OcrEngineKind.ML_KIT_AUTO -> autoRecognize(input)
            OcrEngineKind.BAIDU,
            OcrEngineKind.TENCENT,
            OcrEngineKind.YOUDAO,
            OcrEngineKind.UMI_OCR,
            OcrEngineKind.LUNA_OCR,
            OcrEngineKind.PADDLE_ONNX,
            OcrEngineKind.MANGA_OCR_JA -> autoRecognize(input) // 路由层应当已转走，这里兜底
        }
    }

    private suspend fun autoRecognize(input: InputImage): List<TextBlock> {
        val ko = runRecognizer(korean, input, "ko")
        if (ko.any { containsHangul(it.text) }) return ko
        val ja = runRecognizer(japanese, input, "ja")
        if (ja.any { containsKana(it.text) }) return ja
        val latinResult = runRecognizer(latin, input, "latin")
        if (latinResult.isNotEmpty()) return latinResult
        return runRecognizer(chinese, input, "zh")
    }

    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        input: InputImage,
        lang: String
    ): List<TextBlock> = suspendCancellableCoroutine { cont ->
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.mapNotNull { block ->
                    val bbox = block.boundingBox ?: return@mapNotNull null
                    TextBlock(
                        text = block.text,
                        boundingBox = bbox,
                        confidence = 1f,
                        recognizedLanguage = block.recognizedLanguage ?: lang
                    )
                }
                cont.resume(blocks)
            }
            .addOnFailureListener { e ->
                Timber.w(e, "ML Kit recognize failed ($lang)")
                cont.resumeWithException(e)
            }
    }

    private fun containsKana(s: String): Boolean = s.any { c ->
        // 平假名 3040-309F；片假名 30A0-30FF；半角片假名 FF65-FF9F
        (c in '぀'..'ゟ') ||
            (c in '゠'..'ヿ') ||
            (c in '･'..'ﾟ')
    }

    private fun containsHangul(s: String): Boolean = s.any { c ->
        // 谚文音节 AC00-D7AF；谚文字母 1100-11FF；兼容字母 3130-318F
        (c in '가'..'힯') ||
            (c in 'ᄀ'..'ᇿ') ||
            (c in '㄰'..'㆏')
    }

    override fun close() {
        runCatching { latin.close() }
        runCatching { japanese.close() }
        runCatching { chinese.close() }
        runCatching { korean.close() }
    }
}
