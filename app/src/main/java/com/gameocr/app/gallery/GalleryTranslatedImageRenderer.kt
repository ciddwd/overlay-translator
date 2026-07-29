package com.gameocr.app.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayFontManager
import com.gameocr.app.data.OverlayPlacement
import com.gameocr.app.data.OverlayTextAlignment
import com.gameocr.app.data.OverlayTextStyle
import com.gameocr.app.data.Settings
import com.gameocr.app.data.effectiveOverlayRenderSettings
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.overlay.ADAPTIVE_MIN_TEXT_SIZE_SP
import com.gameocr.app.overlay.AdaptiveOverlayStyle
import com.gameocr.app.overlay.AdaptiveOverlayStyleAnalyzer
import com.gameocr.app.overlay.VerticalTextDrawer
import com.gameocr.app.overlay.horizontalRtlDisplayText
import com.gameocr.app.overlay.normalizeVerticalOverlayText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class GalleryTranslatedImageRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val overlayFontManager: OverlayFontManager,
) {

    fun render(
        source: Bitmap,
        processedWidth: Int,
        processedHeight: Int,
        segments: List<GalleryTranslationSegment>,
        settings: Settings,
    ): Bitmap {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        require(galleryExportRenderMode(settings) != GalleryExportRenderMode.UNSUPPORTED_FLOATING) {
            "Floating-window tasks do not support translated-image export."
        }

        val coordinateWidth = processedWidth.takeIf { it > 0 } ?: source.width
        val coordinateHeight = processedHeight.takeIf { it > 0 } ?: source.height
        val scaleX = source.width.toFloat() / coordinateWidth
        val scaleY = source.height.toFloat() / coordinateHeight
        val renderScale = min(scaleX, scaleY).takeIf { it > 0f } ?: 1f
        val effectiveSettings = settings.effectiveOverlayRenderSettings()
        val mode = galleryExportRenderMode(settings)
        val baseSegments = segments.mapNotNull { segment ->
            val translatedText = normalizeGalleryExportText(segment.translatedText)
            if (translatedText.isEmpty()) return@mapNotNull null
            val bounds = scaledGalleryRect(
                rect = segment.boundingBox,
                sourceWidth = coordinateWidth,
                sourceHeight = coordinateHeight,
                targetWidth = source.width,
                targetHeight = source.height,
            )?.toAndroidRect() ?: return@mapNotNull null
            val sourceBoxes = segment.sourceBoxes
                .ifEmpty { listOf(segment.boundingBox) }
                .mapNotNull { sourceBox ->
                    scaledGalleryRect(
                        rect = sourceBox,
                        sourceWidth = coordinateWidth,
                        sourceHeight = coordinateHeight,
                        targetWidth = source.width,
                        targetHeight = source.height,
                    )?.let {
                        expandedGalleryEraseRect(it, source.width, source.height)
                    }?.toAndroidRect()
                }
                .ifEmpty { listOf(Rect(bounds)) }
            GalleryRenderSegment(
                source = segment,
                translatedText = translatedText,
                sourceBounds = bounds,
                renderBounds = Rect(bounds),
                sourceBoxes = sourceBoxes,
                orientation = galleryExportOrientation(settings, segment.layoutOrientation),
            )
        }
        val renderSegments = baseSegments.mapNotNull { segment ->
            val renderBounds = resolveRenderBounds(
                segment = segment,
                allSourceBounds = baseSegments.map(GalleryRenderSegment::sourceBounds),
                settings = effectiveSettings,
                scaleX = scaleX,
                scaleY = scaleY,
                imageWidth = source.width,
                imageHeight = source.height,
            ) ?: return@mapNotNull null
            segment.copy(renderBounds = renderBounds)
        }

        val output = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true)) {
            "Unable to create translated image."
        }
        if (renderSegments.isEmpty()) return output

        val scaledDensity = (
            context.resources.displayMetrics.scaledDensity * renderScale
            ).coerceAtLeast(0.1f)
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val adaptiveStyles = if (mode == GalleryExportRenderMode.ADAPTIVE_BLOCKS) {
            AdaptiveOverlayStyleAnalyzer.analyze(
                bitmap = source,
                blocks = renderSegments.map { segment ->
                    TextBlock(
                        text = segment.source.sourceText,
                        boundingBox = Rect(segment.sourceBounds),
                        confidence = segment.source.confidence,
                        recognizedLanguage = segment.source.recognizedLanguage,
                        sourceBoxes = segment.sourceBoxes.map(::Rect),
                    )
                },
                scaledDensity = scaledDensity,
            )
        } else {
            emptyList()
        }
        val palette = galleryExportPalette(settings, density, renderScale)
        val typeface = overlayFontManager.typefaceFor(settings)
        val canvas = Canvas(output)

        renderSegments.forEachIndexed { index, segment ->
            val adaptiveStyle = adaptiveStyles.getOrNull(index)
            if (adaptiveStyle != null) {
                drawAdaptiveBackground(canvas, segment, adaptiveStyle)
            } else {
                drawFixedBackground(
                    canvas = canvas,
                    bounds = segment.renderBounds,
                    palette = palette,
                    density = density,
                    renderScale = renderScale,
                )
            }
        }
        renderSegments.forEachIndexed { index, segment ->
            val adaptiveStyle = adaptiveStyles.getOrNull(index)
            val foregroundColor = adaptiveStyle?.foregroundColor ?: palette.foregroundColor
            val maximumTextSizePx = (
                (adaptiveStyle?.maxTextSizeSp ?: effectiveSettings.overlayTextSizeSp.toFloat()) *
                    scaledDensity
                ).roundToInt().coerceAtLeast(1)
            val minimumTextSizePx = (
                ADAPTIVE_MIN_TEXT_SIZE_SP * scaledDensity
                ).roundToInt().coerceIn(1, maximumTextSizePx)
            val textStyle = effectiveSettings.overlayTextStyle.normalized()
            if (segment.orientation.isGalleryVertical()) {
                drawVerticalTranslation(
                    canvas = canvas,
                    text = normalizeVerticalOverlayText(segment.translatedText),
                    bounds = segment.renderBounds,
                    color = foregroundColor,
                    leftToRight = segment.orientation == TextOrientation.VERTICAL_LTR,
                    typeface = typeface,
                    textStyle = textStyle,
                    minimumTextSizePx = minimumTextSizePx,
                    maximumTextSizePx = maximumTextSizePx,
                    density = density,
                    renderScale = renderScale,
                )
            } else {
                drawHorizontalTranslation(
                    canvas = canvas,
                    text = if (segment.orientation == TextOrientation.HORIZONTAL_RTL) {
                        horizontalRtlDisplayText(segment.translatedText)
                    } else {
                        segment.translatedText
                    },
                    bounds = segment.renderBounds,
                    color = foregroundColor,
                    typeface = typeface,
                    textStyle = textStyle,
                    allowWrap = effectiveSettings.overlayAllowWrap,
                    minimumTextSizePx = minimumTextSizePx,
                    maximumTextSizePx = maximumTextSizePx,
                    density = density,
                    renderScale = renderScale,
                )
            }
        }
        return output
    }

    private fun drawAdaptiveBackground(
        canvas: Canvas,
        segment: GalleryRenderSegment,
        style: AdaptiveOverlayStyle,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.backgroundColor
            this.style = Paint.Style.FILL
        }
        segment.sourceBoxes.forEach { sourceBox ->
            val radius = min(sourceBox.width(), sourceBox.height()) * CORNER_RADIUS_RATIO
            canvas.drawRoundRect(
                sourceBox.left.toFloat(),
                sourceBox.top.toFloat(),
                sourceBox.right.toFloat(),
                sourceBox.bottom.toFloat(),
                radius,
                radius,
                paint,
            )
        }
    }

    private fun drawFixedBackground(
        canvas: Canvas,
        bounds: Rect,
        palette: GalleryExportPalette,
        density: Float,
        renderScale: Float,
    ) {
        val radius = min(bounds.width(), bounds.height()) * CORNER_RADIUS_RATIO
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.backgroundColor
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            radius,
            radius,
            fill,
        )
        if (palette.borderWidthPx <= 0 || palette.borderColor ushr 24 == 0) return

        val halfStroke = palette.borderWidthPx / 2f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.borderColor
            style = Paint.Style.STROKE
            strokeWidth = palette.borderWidthPx.toFloat()
            pathEffect = when (palette.borderStyle) {
                BorderStyle.DASHED -> DashPathEffect(
                    floatArrayOf(8f, 5f).map { it * density * renderScale }.toFloatArray(),
                    0f,
                )
                BorderStyle.DOTTED -> DashPathEffect(
                    floatArrayOf(2f, 3f).map { it * density * renderScale }.toFloatArray(),
                    0f,
                )
                BorderStyle.SOLID,
                BorderStyle.DOUBLE,
                BorderStyle.GROOVE,
                -> null
            }
        }
        canvas.drawRoundRect(
            bounds.left + halfStroke,
            bounds.top + halfStroke,
            bounds.right - halfStroke,
            bounds.bottom - halfStroke,
            radius,
            radius,
            border,
        )
    }

    private fun drawHorizontalTranslation(
        canvas: Canvas,
        text: String,
        bounds: Rect,
        color: Int,
        typeface: Typeface?,
        textStyle: OverlayTextStyle,
        allowWrap: Boolean,
        minimumTextSizePx: Int,
        maximumTextSizePx: Int,
        density: Float,
        renderScale: Float,
    ) {
        val padding = galleryTextPadding(bounds.width(), bounds.height())
        val contentWidth = (bounds.width() - padding * 2).coerceAtLeast(1)
        val contentHeight = (bounds.height() - padding * 2).coerceAtLeast(1)
        val displayText = if (allowWrap) text else text.replace('\n', ' ')
        val paint = createTextPaint(
            color = color,
            typeface = typeface,
            textStyle = textStyle,
            density = density,
            renderScale = renderScale,
        )
        val maxTextSize = min(
            maximumTextSizePx,
            maxOf(contentWidth, contentHeight),
        ).coerceAtLeast(minimumTextSizePx)
        val textSize = largestFittingTextSize(
            minimum = minimumTextSizePx,
            maximum = maxTextSize,
        ) { candidate ->
            paint.textSize = candidate.toFloat()
            val layout = buildHorizontalLayout(
                text = displayText,
                paint = paint,
                width = contentWidth,
                textStyle = textStyle,
                allowWrap = allowWrap,
            )
            layout.height <= contentHeight &&
                (
                    allowWrap ||
                        (
                            layout.lineCount == 1 &&
                                layout.getLineEnd(0) >= displayText.length &&
                                layout.getLineWidth(0) <= contentWidth
                            )
                    )
        }
        paint.textSize = textSize.toFloat()
        val layout = buildHorizontalLayout(
            text = displayText,
            paint = paint,
            width = contentWidth,
            textStyle = textStyle,
            allowWrap = allowWrap,
        )
        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(
            (bounds.left + padding).toFloat(),
            bounds.top + padding + ((contentHeight - layout.height).coerceAtLeast(0) / 2f),
        )
        drawHorizontalLayoutWithEffects(
            canvas = canvas,
            layout = layout,
            paint = paint,
            foregroundColor = color,
            textStyle = textStyle,
            density = density,
            renderScale = renderScale,
        )
        canvas.restore()
    }

    private fun drawVerticalTranslation(
        canvas: Canvas,
        text: String,
        bounds: Rect,
        color: Int,
        leftToRight: Boolean,
        typeface: Typeface?,
        textStyle: OverlayTextStyle,
        minimumTextSizePx: Int,
        maximumTextSizePx: Int,
        density: Float,
        renderScale: Float,
    ) {
        val padding = galleryTextPadding(bounds.width(), bounds.height())
        val contentWidth = (bounds.width() - padding * 2).coerceAtLeast(1)
        val contentHeight = (bounds.height() - padding * 2).coerceAtLeast(1)
        val paint = createPaint(
            color = color,
            typeface = typeface,
            textStyle = textStyle,
            density = density,
            renderScale = renderScale,
        )
        val maxTextSize = min(
            maximumTextSizePx,
            maxOf(contentWidth, contentHeight),
        ).coerceAtLeast(minimumTextSizePx)
        val textSize = largestFittingTextSize(
            minimum = minimumTextSizePx,
            maximum = maxTextSize,
        ) { candidate ->
            paint.textSize = candidate.toFloat()
            val measured = VerticalTextDrawer.measure(
                text = text,
                paint = paint,
                maxHeightPx = contentHeight,
                letterSpacingEm = textStyle.letterSpacingEm,
                lineSpacingMultiplier = textStyle.lineSpacingMultiplier,
            )
            measured.first <= contentWidth && measured.second <= contentHeight
        }
        paint.textSize = textSize.toFloat()
        canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(
            (bounds.left + padding).toFloat(),
            (bounds.top + padding).toFloat(),
        )
        drawVerticalWithEffects(
            canvas = canvas,
            text = text,
            paint = paint,
            boundsW = contentWidth.toFloat(),
            boundsH = contentHeight.toFloat(),
            leftToRight = leftToRight,
            foregroundColor = color,
            textStyle = textStyle,
            density = density,
            renderScale = renderScale,
        )
        canvas.restore()
    }

    private fun buildHorizontalLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        textStyle: OverlayTextStyle,
        allowWrap: Boolean,
    ): StaticLayout {
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(textStyle.alignment.toLayoutAlignment())
            .setIncludePad(false)
            .setLineSpacing(0f, textStyle.lineSpacingMultiplier)
        if (!allowWrap) builder.setMaxLines(1)
        return builder.build()
    }

    private fun createTextPaint(
        color: Int,
        typeface: Typeface?,
        textStyle: OverlayTextStyle,
        density: Float,
        renderScale: Float,
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        applyTextPaintSettings(
            color = color,
            typeface = typeface,
            textStyle = textStyle,
            density = density,
            renderScale = renderScale,
        )
        letterSpacing = textStyle.letterSpacingEm
    }

    private fun createPaint(
        color: Int,
        typeface: Typeface?,
        textStyle: OverlayTextStyle,
        density: Float,
        renderScale: Float,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        applyTextPaintSettings(
            color = color,
            typeface = typeface,
            textStyle = textStyle,
            density = density,
            renderScale = renderScale,
        )
    }

    private fun Paint.applyTextPaintSettings(
        color: Int,
        typeface: Typeface?,
        textStyle: OverlayTextStyle,
        density: Float,
        renderScale: Float,
    ) {
        this.color = color
        this.typeface = styledTypeface(typeface, textStyle)
        isUnderlineText = textStyle.underline
        style = Paint.Style.FILL
        applyShadow(textStyle, density, renderScale)
    }

    private fun styledTypeface(
        typeface: Typeface?,
        textStyle: OverlayTextStyle,
    ): Typeface {
        val style = when {
            textStyle.bold && textStyle.italic -> Typeface.BOLD_ITALIC
            textStyle.bold -> Typeface.BOLD
            textStyle.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(typeface ?: Typeface.DEFAULT, style)
    }

    private fun drawHorizontalLayoutWithEffects(
        canvas: Canvas,
        layout: StaticLayout,
        paint: TextPaint,
        foregroundColor: Int,
        textStyle: OverlayTextStyle,
        density: Float,
        renderScale: Float,
    ) {
        if (textStyle.strokeEnabled) {
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = textStyle.strokeWidthDp * density * renderScale
            paint.color = galleryColorWithMultipliedAlpha(
                textStyle.strokeColor,
                (foregroundColor ushr 24 and 0xFF) / 255f,
            )
            layout.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = foregroundColor
        paint.applyShadow(textStyle, density, renderScale)
        layout.draw(canvas)
    }

    private fun drawVerticalWithEffects(
        canvas: Canvas,
        text: String,
        paint: Paint,
        boundsW: Float,
        boundsH: Float,
        leftToRight: Boolean,
        foregroundColor: Int,
        textStyle: OverlayTextStyle,
        density: Float,
        renderScale: Float,
    ) {
        fun draw() {
            VerticalTextDrawer.draw(
                canvas = canvas,
                text = text,
                paint = paint,
                boundsW = boundsW,
                boundsH = boundsH,
                leftToRight = leftToRight,
                letterSpacingEm = textStyle.letterSpacingEm,
                lineSpacingMultiplier = textStyle.lineSpacingMultiplier,
                alignment = textStyle.alignment,
            )
        }
        if (textStyle.strokeEnabled) {
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = textStyle.strokeWidthDp * density * renderScale
            paint.color = galleryColorWithMultipliedAlpha(
                textStyle.strokeColor,
                (foregroundColor ushr 24 and 0xFF) / 255f,
            )
            draw()
        }
        paint.style = Paint.Style.FILL
        paint.color = foregroundColor
        paint.applyShadow(textStyle, density, renderScale)
        draw()
    }

    private fun Paint.applyShadow(
        textStyle: OverlayTextStyle,
        density: Float,
        renderScale: Float,
    ) {
        if (!textStyle.shadowEnabled) {
            clearShadowLayer()
            return
        }
        val foregroundAlpha = (color ushr 24 and 0xFF) / 255f
        setShadowLayer(
            textStyle.shadowRadiusDp * density * renderScale,
            textStyle.shadowOffsetXDp * density * renderScale,
            textStyle.shadowOffsetYDp * density * renderScale,
            galleryColorWithMultipliedAlpha(textStyle.shadowColor, foregroundAlpha),
        )
    }

    private fun resolveRenderBounds(
        segment: GalleryRenderSegment,
        allSourceBounds: List<Rect>,
        settings: Settings,
        scaleX: Float,
        scaleY: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): Rect? {
        val source = segment.sourceBounds
        val placement = galleryExportPlacement(settings.overlayPlacement, segment.orientation)
        val target = Rect(source)
        when (placement) {
            OverlayPlacement.BELOW -> target.offsetTo(source.left, source.bottom)
            OverlayPlacement.OVERLAP -> Unit
            OverlayPlacement.ABOVE -> target.offsetTo(source.left, source.top - source.height())
        }
        target.offset(
            (settings.overlayOffsetX * scaleX).roundToInt(),
            (settings.overlayOffsetY * scaleY).roundToInt(),
        )
        if (settings.overlayAvoidCollision && placement != OverlayPlacement.OVERLAP) {
            constrainAgainstSourceBoxes(
                target = target,
                source = source,
                obstacles = allSourceBounds,
                placement = placement,
                gapPx = (4f * min(scaleX, scaleY)).roundToInt().coerceAtLeast(1),
            )
        }
        fitRectInside(target, imageWidth, imageHeight)
        return target.takeIf { it.width() > 0 && it.height() > 0 }
    }

    private fun constrainAgainstSourceBoxes(
        target: Rect,
        source: Rect,
        obstacles: List<Rect>,
        placement: OverlayPlacement,
        gapPx: Int,
    ) {
        val candidates = obstacles.filter { obstacle ->
            obstacle != source &&
                obstacle.right > target.left &&
                obstacle.left < target.right
        }
        when (placement) {
            OverlayPlacement.BELOW -> candidates
                .filter { it.top >= source.bottom && it.top < target.bottom }
                .minOfOrNull(Rect::top)
                ?.let { boundary ->
                    target.bottom = maxOf(target.top + 1, boundary - gapPx)
                }
            OverlayPlacement.ABOVE -> candidates
                .filter { it.bottom <= source.top && it.bottom > target.top }
                .maxOfOrNull(Rect::bottom)
                ?.let { boundary ->
                    target.top = minOf(target.bottom - 1, boundary + gapPx)
                }
            OverlayPlacement.OVERLAP -> Unit
        }
    }

    private fun fitRectInside(rect: Rect, imageWidth: Int, imageHeight: Int) {
        if (rect.width() >= imageWidth) {
            rect.left = 0
            rect.right = imageWidth
        } else {
            if (rect.left < 0) rect.offset(-rect.left, 0)
            if (rect.right > imageWidth) rect.offset(imageWidth - rect.right, 0)
        }
        if (rect.height() >= imageHeight) {
            rect.top = 0
            rect.bottom = imageHeight
        } else {
            if (rect.top < 0) rect.offset(0, -rect.top)
            if (rect.bottom > imageHeight) rect.offset(0, imageHeight - rect.bottom)
        }
        rect.intersect(0, 0, imageWidth, imageHeight)
    }

    private fun OverlayTextAlignment.toLayoutAlignment(): Layout.Alignment = when (this) {
        OverlayTextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
        OverlayTextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
        OverlayTextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
    }

    private data class GalleryRenderSegment(
        val source: GalleryTranslationSegment,
        val translatedText: String,
        val sourceBounds: Rect,
        val renderBounds: Rect,
        val sourceBoxes: List<Rect>,
        val orientation: TextOrientation,
    )

    private companion object {
        const val CORNER_RADIUS_RATIO = 0.08f
    }
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

internal fun expandedGalleryEraseRect(
    rect: GalleryRect,
    imageWidth: Int,
    imageHeight: Int,
): GalleryRect {
    val margin = (min(rect.right - rect.left, rect.bottom - rect.top) * 0.08f)
        .roundToInt()
        .coerceIn(2, 12)
    return GalleryRect(
        left = (rect.left - margin).coerceIn(0, imageWidth),
        top = (rect.top - margin).coerceIn(0, imageHeight),
        right = (rect.right + margin).coerceIn(0, imageWidth),
        bottom = (rect.bottom + margin).coerceIn(0, imageHeight),
    )
}

internal fun normalizeGalleryExportText(text: String): String =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { line ->
            line.trim().replace(Regex("""[ \t]+"""), " ")
        }
        .trim()

internal fun largestFittingTextSize(
    minimum: Int,
    maximum: Int,
    fits: (Int) -> Boolean,
): Int {
    var low = minimum.coerceAtLeast(1)
    var high = maximum.coerceAtLeast(low)
    var best = low
    while (low <= high) {
        val candidate = low + (high - low) / 2
        if (fits(candidate)) {
            best = candidate
            low = candidate + 1
        } else {
            high = candidate - 1
        }
    }
    return best
}

private fun galleryTextPadding(width: Int, height: Int): Int =
    (min(width, height) * 0.05f).roundToInt().coerceIn(2, 16)
