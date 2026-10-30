package com.gameocr.app.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import com.gameocr.app.data.OverlayFontManager
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.data.adaptiveOverlayActive
import com.gameocr.app.ocr.MangaOcrEngine
import com.gameocr.app.ocr.PageOcrFrame
import com.gameocr.app.ocr.PageOcrPipeline
import com.gameocr.app.ocr.resolveTextBlockReadingOrientation
import com.gameocr.app.ocr.TranslationOutputOrientationPolicy
import com.gameocr.app.ocr.ShapeAwareBubblePatch
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.ocr.TextRegionGranularity
import com.gameocr.app.ocr.supportsShapeAwareBubblePatches
import com.gameocr.app.overlay.OverlayManager
import com.gameocr.app.translate.RoutingTranslator
import com.gameocr.app.translate.PageTranslationPreparer
import com.gameocr.app.translate.PreparedPageTranslation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** File input/output adapter for the screen translation's repair, layout and block renderer. */
@Singleton
class GalleryTranslatedImageRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlayFontManager: OverlayFontManager,
    private val settingsRepository: SettingsRepository,
    private val pageOcrPipeline: PageOcrPipeline,
    private val mangaOcrEngine: MangaOcrEngine,
    private val translator: RoutingTranslator,
    private val pageTranslationPreparer: PageTranslationPreparer,
) {
    internal suspend fun render(
        source: Bitmap,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
        settings: Settings,
        frame: PageOcrFrame? = null,
        preparedPage: PreparedPageTranslation? = null,
        rebuildVisualContext: Boolean = true,
    ): Bitmap = withContext(Dispatchers.Default) {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        require(galleryExportRenderMode(settings) != GalleryExportRenderMode.UNSUPPORTED_FLOATING)
        val translated = segments.mapIndexedNotNull { index, segment ->
            val block = preparedPage?.blocks?.getOrNull(index)
                ?: segment.toTextBlock(processedWidth, processedHeight, source.width, source.height)
            block?.let { it to if (preparedPage != null) segment.translatedText
                else normalizeGalleryExportText(segment.translatedText) }
        }
        val blocks = translated.map { it.first }
        val successful = translated.indices.filterTo(linkedSetOf()) { translated[it].second.isNotBlank() }
        val adaptive = adaptiveOverlayActive(settings.overlayStyleMode, settings.renderMode)
        val shapeEnabled = adaptive && !translator.isEndToEndFor(settings) &&
            supportsShapeAwareBubblePatches(frame?.engine ?: settings.ocrEngine) &&
            mangaOcrEngine.isShapeAwareRenderingEnabled(settings)
        val renderFrame = frame ?: if (shapeEnabled && rebuildVisualContext && blocks.isNotEmpty()) {
            // Recover OCR geometry/masks without requesting another translation.
            pageOcrPipeline.recognize(source, settings).frame
        } else null
        val storedPageDirection = segments.firstNotNullOfOrNull { it.pageReadingOrientation }
            ?.let { runCatching { TextOrientation.valueOf(it) }.getOrNull() }
        val recognizedDirection = renderFrame?.readingOrientation ?: resolveTextBlockReadingOrientation(
            blocks, storedPageDirection ?: settings.manualTextOrientation.takeIf { settings.textOrientationAutoDetect })
        val orientation = TranslationOutputOrientationPolicy.resolve(recognizedDirection,
            settings.translationOutputFollowRecognition, settings.translationOutputLayout, settings.translationOutputDirection)
        val batch = if (shapeEnabled) renderFrame?.claim(blocks) else null
        var patches = emptyList<ShapeAwareBubblePatch>()
        var backgroundPatches = emptyList<ShapeAwareBubblePatch>()
        if (batch != null) {
            // These are the same methods used by CaptureService, including boundary protection,
            // local text repair, model rescue, shape layout and untranslated-block fallbacks.
            val activeFrame = requireNotNull(renderFrame)
            val manager = activeFrame.manager
            val prepared = manager.prepare(batch, blocks.indices.toSet())
            backgroundPatches = activeFrame.mapPatchesToDisplay(prepared.backgroundPatches)
            manager.finish(
                prepared = prepared,
                successfulBlockIndices = successful,
                translatedBlockTexts = successful.associateWith { translated[it].second },
                outputOrientation = orientation,
                followBlockOrientations = settings.translationOutputFollowRecognition,
                displayPatches = { patches = activeFrame.mapPatchesToDisplay(it); it.size },
            )
        }
        val styles = preparedPage?.adaptiveStyles ?: pageTranslationPreparer.analyzeStyles(source, blocks, settings)
        val typeface = overlayFontManager.typefaceFor(settings)
        val renderTranslated = translated.map { pair ->
            pair.first to pair.second.ifBlank { pair.first.text }
        }
        withContext(Dispatchers.Main) {
            coroutineScope {
                val overlay = OverlayManager(context, settingsRepository, this,
                    bitmapTargetSize = Size(source.width, source.height))
                overlay.applyBlockRenderSettings(settings, typeface)
                overlay.drawBlocksToBitmap(source, renderTranslated, orientation, styles,
                    settings.translationOutputFollowRecognition, batch != null, patches,
                    backgroundPatches = backgroundPatches)
            }
        }
    }
}

internal fun GalleryTranslationSegment.toTextBlock(
    processedWidth: Int,
    processedHeight: Int,
    width: Int,
    height: Int,
): TextBlock? {
    fun GalleryRect.scaled(): Rect? = scaledGalleryRect(this,
        processedWidth.takeIf { it > 0 } ?: width,
        processedHeight.takeIf { it > 0 } ?: height, width, height)?.toAndroidRect()
    return TextBlock(
        text = sourceText,
        boundingBox = boundingBox.scaled() ?: return null,
        confidence = confidence,
        recognizedLanguage = recognizedLanguage,
        layoutOrientation = layoutOrientation?.let { runCatching { TextOrientation.valueOf(it) }.getOrNull() },
        sourceBoxes = sourceBoxes.mapNotNull { it.scaled() },
        bubbleGroupId = bubbleGroupId,
        regionId = regionId,
        parentRegionId = parentRegionId,
        regionGranularity = runCatching { TextRegionGranularity.valueOf(regionGranularity) }
            .getOrDefault(TextRegionGranularity.UNKNOWN),
        presentationRotationDegrees = presentationRotationDegrees,
    )
}

internal fun scaledGalleryRect(
    rect: GalleryRect,
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): GalleryRect? {
    if (
        sourceWidth <= 0 ||
        sourceHeight <= 0 ||
        targetWidth <= 0 ||
        targetHeight <= 0 ||
        rect.right <= rect.left ||
        rect.bottom <= rect.top
    ) {
        return null
    }
    val scaleX = targetWidth.toDouble() / sourceWidth
    val scaleY = targetHeight.toDouble() / sourceHeight
    val left = floor(rect.left * scaleX).toInt().coerceIn(0, targetWidth)
    val top = floor(rect.top * scaleY).toInt().coerceIn(0, targetHeight)
    val right = ceil(rect.right * scaleX).toInt().coerceIn(0, targetWidth)
    val bottom = ceil(rect.bottom * scaleY).toInt().coerceIn(0, targetHeight)
    return if (right > left && bottom > top) {
        GalleryRect(left, top, right, bottom)
    } else {
        null
    }
}

internal fun normalizeGalleryExportText(text: String): String =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { line ->
            line.trim().replace(Regex("""[ \t]+"""), " ")
        }
        .trim()
