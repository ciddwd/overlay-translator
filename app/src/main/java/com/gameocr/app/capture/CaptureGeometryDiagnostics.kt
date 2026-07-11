package com.gameocr.app.capture

import java.util.Locale

internal enum class CaptureCoordinateRelation {
    MATCH,
    ORIENTATION_MISMATCH,
    SCALED_SAME_ASPECT,
    ASPECT_RATIO_MISMATCH,
    INVALID
}

internal data class CaptureGeometryDiagnostic(
    val relation: CaptureCoordinateRelation,
    val frameWidth: Int,
    val frameHeight: Int,
    val overlayWidth: Int,
    val overlayHeight: Int,
    val scaleX: Float,
    val scaleY: Float
) {
    fun toDiagString(): String =
        "frame=${frameWidth}x$frameHeight overlay=${overlayWidth}x$overlayHeight " +
            "relation=${relation.name} scale=(${scaleX.toDiagScale()},${scaleY.toDiagScale()})"
}

internal fun diagnoseCaptureGeometry(
    frameWidth: Int,
    frameHeight: Int,
    overlayWidth: Int,
    overlayHeight: Int
): CaptureGeometryDiagnostic {
    if (frameWidth <= 0 || frameHeight <= 0 || overlayWidth <= 0 || overlayHeight <= 0) {
        return CaptureGeometryDiagnostic(
            relation = CaptureCoordinateRelation.INVALID,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            scaleX = 0f,
            scaleY = 0f
        )
    }

    val scaleX = overlayWidth.toFloat() / frameWidth
    val scaleY = overlayHeight.toFloat() / frameHeight
    val frameOrientation = frameWidth.compareTo(frameHeight)
    val overlayOrientation = overlayWidth.compareTo(overlayHeight)
    val relation = when {
        frameWidth == overlayWidth && frameHeight == overlayHeight ->
            CaptureCoordinateRelation.MATCH
        frameOrientation != 0 && overlayOrientation != 0 && frameOrientation != overlayOrientation ->
            CaptureCoordinateRelation.ORIENTATION_MISMATCH
        kotlin.math.abs(scaleX - scaleY) <= SAME_ASPECT_SCALE_TOLERANCE ->
            CaptureCoordinateRelation.SCALED_SAME_ASPECT
        else -> CaptureCoordinateRelation.ASPECT_RATIO_MISMATCH
    }
    return CaptureGeometryDiagnostic(
        relation = relation,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        overlayWidth = overlayWidth,
        overlayHeight = overlayHeight,
        scaleX = scaleX,
        scaleY = scaleY
    )
}

internal fun shouldResizeProjection(
    currentWidth: Int,
    currentHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Boolean =
    targetWidth > 0 && targetHeight > 0 &&
        (currentWidth != targetWidth || currentHeight != targetHeight)

private fun Float.toDiagScale(): String = String.format(Locale.US, "%.4f", this)

private const val SAME_ASPECT_SCALE_TOLERANCE = 0.01f
