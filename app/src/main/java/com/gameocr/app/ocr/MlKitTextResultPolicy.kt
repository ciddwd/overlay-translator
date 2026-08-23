package com.gameocr.app.ocr

import kotlin.math.abs

internal object MlKitTextResultPolicy {
    private const val VERTICAL_ASPECT_RATIO = 1.25f
    private const val VERTICAL_ANGLE_TOLERANCE_DEGREES = 30f

    fun confidence(
        lineConfidence: Float,
        elementConfidences: List<Float>,
    ): Float {
        if (lineConfidence.isFinite() && lineConfidence >= 0f) {
            return lineConfidence.coerceIn(0f, 1f)
        }
        val validElements = elementConfidences.filter { it.isFinite() && it >= 0f }
        return if (validElements.isEmpty()) {
            0f
        } else {
            (validElements.average().toFloat()).coerceIn(0f, 1f)
        }
    }

    fun effectiveLanguage(
        detectedLineLanguage: String?,
        detectedBlockLanguage: String?,
        configuredLanguage: String,
    ): String = sequenceOf(detectedLineLanguage, detectedBlockLanguage)
        .mapNotNull { language ->
            language
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        }
        .firstOrNull()
        ?: configuredLanguage

    fun layoutOrientation(
        width: Int,
        height: Int,
        angleDegrees: Float,
        languageTag: String,
        geometryPoints: List<MlKitGeometryPoint> = emptyList(),
    ): TextOrientation {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val geometryAxis = MlKitJapaneseGeometryPolicy.dominantAxis(geometryPoints)
        if (geometryAxis != MlKitGeometryAxis.UNKNOWN) {
            return when (geometryAxis) {
                MlKitGeometryAxis.VERTICAL -> if (isCjkScript(languageTag)) {
                    TextOrientation.VERTICAL_RTL
                } else {
                    TextOrientation.STACKED
                }
                MlKitGeometryAxis.HORIZONTAL -> TextOrientation.HORIZONTAL_LTR
                MlKitGeometryAxis.UNKNOWN -> error("handled above")
            }
        }
        val normalizedAngle = normalizeHalfTurn(angleDegrees)
        val verticalByAngle = abs(abs(normalizedAngle) - 90f) <= VERTICAL_ANGLE_TOLERANCE_DEGREES
        val verticalByShape = safeHeight >= safeWidth * VERTICAL_ASPECT_RATIO
        if (!verticalByAngle && !verticalByShape) return TextOrientation.HORIZONTAL_LTR

        return if (isCjkScript(languageTag)) {
            TextOrientation.VERTICAL_RTL
        } else {
            TextOrientation.STACKED
        }
    }

    private fun isCjkScript(languageTag: String): Boolean {
        val script = languageTag.trim().substringBefore('-').lowercase()
        return script == "ja" || script == "zh" || script == "ko"
    }

    private fun normalizeHalfTurn(angleDegrees: Float): Float {
        if (!angleDegrees.isFinite()) return 0f
        var normalized = angleDegrees % 180f
        if (normalized > 90f) normalized -= 180f
        if (normalized < -90f) normalized += 180f
        return normalized
    }
}
