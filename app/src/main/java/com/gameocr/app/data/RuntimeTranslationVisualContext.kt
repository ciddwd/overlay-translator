package com.gameocr.app.data

/** Request-scoped image context. It is deliberately excluded from persisted settings. */
data class RuntimeTranslationVisualContext(
    val mimeType: String,
    val base64Data: String,
    val width: Int,
    val height: Int,
    val byteCount: Int,
    val sha256: String,
    val items: List<RuntimeVisualTextItem>,
    val combineIntoSingleOutput: Boolean,
    val promptVersion: Int = CURRENT_PROMPT_VERSION,
) {
    companion object {
        const val CURRENT_PROMPT_VERSION: Int = 1
    }
}

/** Coordinates are normalized to 0..1000 so they stay valid after image resizing. */
data class RuntimeVisualTextItem(
    val id: Int,
    val source: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)
