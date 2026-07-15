package com.gameocr.app.capture

import com.gameocr.app.data.LoopTextRegionMode
import kotlin.math.max
import kotlin.math.min

internal data class LoopTextRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isValid: Boolean get() = width > 0 && height > 0

    fun union(other: LoopTextRect): LoopTextRect = LoopTextRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )
}

internal data class LoopTextRoiCandidate(
    val text: String,
    val rect: LoopTextRect,
)

internal object LoopTextRoiPolicy {
    private const val MIN_PRIMARY_CHAR_COUNT = 4
    private const val MIN_LOWER_BAND_CHAR_COUNT = 2
    private const val LOWER_BAND_START_RATIO = 0.55f
    private const val MIN_ROI_WIDTH_RATIO = 0.55f
    private const val MIN_ROI_HEIGHT_RATIO = 0.20f

    fun select(
        candidates: List<LoopTextRoiCandidate>,
        imageWidth: Int,
        imageHeight: Int,
        mode: LoopTextRegionMode = LoopTextRegionMode.AUTO,
    ): LoopTextRect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val valid = candidates.filter { candidate ->
            candidate.rect.isValid && compactLength(candidate.text) >= MIN_LOWER_BAND_CHAR_COUNT
        }
        val lowerBand = valid.filter { candidate ->
            centerY(candidate.rect) >= imageHeight * LOWER_BAND_START_RATIO
        }
        val anywhere = valid.filter { candidate ->
            compactLength(candidate.text) >= MIN_PRIMARY_CHAR_COUNT
        }
        val primaryPool = when (mode) {
            LoopTextRegionMode.AUTO -> valid
            LoopTextRegionMode.LOWER_SCREEN_FIRST -> lowerBand.ifEmpty { anywhere }
            LoopTextRegionMode.ANYWHERE -> anywhere
        }
        val primary = primaryPool.maxByOrNull { candidate ->
            score(candidate, imageHeight, usePosition = mode != LoopTextRegionMode.ANYWHERE)
        } ?: return null
        val nearby = valid.filter { candidate ->
            candidate === primary || belongsToDialogueGroup(primary.rect, candidate.rect)
        }
        val content = nearby.fold(primary.rect) { union, candidate -> union.union(candidate.rect) }

        val horizontalPadding = max(imageWidth / 12, content.width / 4)
        val topPadding = max(imageHeight / 10, content.height)
        val bottomPadding = max(imageHeight / 8, content.height)
        val expanded = LoopTextRect(
            left = content.left - horizontalPadding,
            top = content.top - topPadding,
            right = content.right + horizontalPadding,
            bottom = content.bottom + bottomPadding,
        )
        return ensureMinimumSize(expanded, imageWidth, imageHeight)
    }

    fun containsCenter(roi: LoopTextRect, candidate: LoopTextRect): Boolean {
        val centerX = (candidate.left + candidate.right) / 2
        val centerY = centerY(candidate)
        return centerX in roi.left until roi.right && centerY in roi.top until roi.bottom
    }

    private fun score(
        candidate: LoopTextRoiCandidate,
        imageHeight: Int,
        usePosition: Boolean,
    ): Long {
        val compactChars = compactLength(candidate.text)
        val lineCount = candidate.text.lineSequence().count { it.isNotBlank() }.coerceAtLeast(1)
        return if (usePosition) {
            val verticalPosition = centerY(candidate.rect).toLong() * 5_000L / imageHeight.coerceAtLeast(1)
            compactChars.toLong() * 100L +
                lineCount.toLong() * 1_000L +
                candidate.rect.width.toLong() +
                verticalPosition
        } else {
            compactChars.toLong() * 10_000L +
                lineCount.toLong() * 1_000L +
                candidate.rect.width.toLong()
        }
    }

    private fun compactLength(text: String): Int = text.count { !it.isWhitespace() }

    private fun centerY(rect: LoopTextRect): Int = (rect.top + rect.bottom) / 2

    private fun belongsToDialogueGroup(primary: LoopTextRect, candidate: LoopTextRect): Boolean {
        val overlap = (min(primary.right, candidate.right) - max(primary.left, candidate.left))
            .coerceAtLeast(0)
        val minWidth = min(primary.width, candidate.width).coerceAtLeast(1)
        val horizontalOverlapRatio = overlap.toFloat() / minWidth
        val verticalGap = when {
            candidate.bottom < primary.top -> primary.top - candidate.bottom
            candidate.top > primary.bottom -> candidate.top - primary.bottom
            else -> 0
        }
        return horizontalOverlapRatio >= 0.25f &&
            verticalGap <= max(primary.height, candidate.height) * 2
    }

    private fun ensureMinimumSize(
        rect: LoopTextRect,
        imageWidth: Int,
        imageHeight: Int,
    ): LoopTextRect {
        val targetWidth = max(rect.width, (imageWidth * MIN_ROI_WIDTH_RATIO).toInt())
            .coerceAtMost(imageWidth)
        val targetHeight = max(rect.height, (imageHeight * MIN_ROI_HEIGHT_RATIO).toInt())
            .coerceAtMost(imageHeight)
        val centerX = (rect.left + rect.right) / 2
        val centerY = (rect.top + rect.bottom) / 2
        val left = (centerX - targetWidth / 2).coerceIn(0, imageWidth - targetWidth)
        val top = (centerY - targetHeight / 2).coerceIn(0, imageHeight - targetHeight)
        return LoopTextRect(left, top, left + targetWidth, top + targetHeight)
    }
}

internal object LoopRoiCoordinatePolicy {
    fun mapFromRoi(
        block: LoopTextRect,
        roi: LoopTextRect,
        upscale2x: Boolean,
    ): LoopTextRect {
        val scale = if (upscale2x) 2 else 1
        return LoopTextRect(
            left = roi.left + block.left / scale,
            top = roi.top + block.top / scale,
            right = roi.left + block.right / scale,
            bottom = roi.top + block.bottom / scale,
        )
    }
}
