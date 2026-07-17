package com.gameocr.app.ocr

internal enum class MangaOcrTraceStage(
    val label: String,
    val requiresBubbleIndex: Boolean,
) {
    RUN("run", false),
    DETECT("detect", false),
    CLUSTER("cluster", false),
    BUBBLE("bubble", true),
    ENCODER("encoder", true),
    DECODER("decoder", true),
}

internal object MangaOcrTracePolicy {
    private const val PREFIX = "MangaOCR/"

    fun sectionName(
        stage: MangaOcrTraceStage,
        bubbleIndex: Int? = null,
    ): String {
        val safeIndex = bubbleIndex?.coerceAtLeast(0)
        val suffix = if (stage.requiresBubbleIndex) {
            "[${safeIndex ?: 0}]"
        } else {
            ""
        }
        return "$PREFIX${stage.label}$suffix"
    }
}
