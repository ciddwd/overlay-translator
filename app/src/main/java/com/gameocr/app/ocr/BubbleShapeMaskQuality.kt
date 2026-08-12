package com.gameocr.app.ocr

/** Describes whether a recovered bubble mask is reliable enough to define final patch geometry. */
internal enum class BubbleShapeMaskQuality {
    TRUSTED,
    APPROXIMATE,
    REJECTED,
    ;

    val supportsShapePatch: Boolean
        get() = this == TRUSTED

    val shapePatchRejectionReason: String?
        get() = if (supportsShapePatch) null else "${name}_MODEL_MASK"

    companion object {
        fun fromDetectorDecision(
            accepted: Boolean,
            reason: String,
        ): BubbleShapeMaskQuality = when {
            !accepted -> REJECTED
            reason == APPROXIMATE_ELLIPSE_REASON -> APPROXIMATE
            else -> TRUSTED
        }

        private const val APPROXIMATE_ELLIPSE_REASON = "accepted_ellipse_fallback"
    }
}
