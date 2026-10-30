package com.gameocr.app.gallery

import android.graphics.Rect
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextOrientation
import kotlinx.serialization.Serializable

enum class GalleryTaskStatus {
    QUEUED,
    RUNNING,
    WAITING_RETRY,
    PARTIAL,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

enum class GalleryItemStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

@Serializable
data class GalleryRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun from(rect: Rect): GalleryRect = GalleryRect(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
        )
    }
}

@Serializable
data class GalleryTranslationSegment(
    val sourceText: String,
    val translatedText: String,
    val boundingBox: GalleryRect,
    val sourceBoxes: List<GalleryRect>,
    val confidence: Float,
    val recognizedLanguage: String? = null,
    val layoutOrientation: String? = null,
    val bubbleGroupId: Int? = null,
    val regionId: Int? = null,
    val parentRegionId: Int? = null,
    val regionGranularity: String = "UNKNOWN",
    val presentationRotationDegrees: Int = 0,
    val pageReadingOrientation: String? = null,
) {
    companion object {
        fun from(block: TextBlock, translation: String?, pageReadingOrientation: TextOrientation? = null): GalleryTranslationSegment =
            GalleryTranslationSegment(
                sourceText = block.text,
                translatedText = translation.orEmpty(),
                boundingBox = GalleryRect.from(block.boundingBox),
                sourceBoxes = block.sourceBoxes
                    .map(GalleryRect::from),
                confidence = block.confidence,
                recognizedLanguage = block.recognizedLanguage,
                layoutOrientation = block.layoutOrientation
                    ?.takeUnless { it == TextOrientation.UNKNOWN }
                    ?.name,
                bubbleGroupId = block.bubbleGroupId,
                regionId = block.regionId,
                parentRegionId = block.parentRegionId,
                regionGranularity = block.regionGranularity.name,
                presentationRotationDegrees = block.presentationRotationDegrees,
                pageReadingOrientation = pageReadingOrientation?.name,
            )
    }
}

data class GalleryTaskProgress(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    val completed: Int get() = succeeded + failed
}

data class GalleryPreparedTask(
    val id: String,
    val sourceLang: String,
    val targetLang: String,
    val ocrEngine: OcrEngineKind,
    val translatorEngine: TranslatorEngine,
    val imageCount: Int,
)
