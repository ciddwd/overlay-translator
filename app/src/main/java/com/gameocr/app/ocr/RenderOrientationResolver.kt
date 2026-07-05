package com.gameocr.app.ocr

internal fun orientationHintFromLayout(blocks: List<TextBlock>): OrientationResult? {
    val orientation = dominantLayoutOrientation(blocks.map { it.layoutOrientation }) ?: return null
    return OrientationResult(
        orientation = orientation,
        confidence = 0.85f,
        rawAngle = 0,
        source = "ocr-merge-layout"
    )
}

internal fun resolveRenderOrientation(
    hint: TextOrientation?,
    blockOrientations: List<TextOrientation?>
): TextOrientation {
    val layoutOrientation = dominantLayoutOrientation(blockOrientations)
    if (layoutOrientation == TextOrientation.VERTICAL_RTL ||
        layoutOrientation == TextOrientation.VERTICAL_LTR
    ) {
        return layoutOrientation
    }
    return hint
        ?.takeIf { it != TextOrientation.UNKNOWN }
        ?: layoutOrientation
        ?: TextOrientation.HORIZONTAL_LTR
}

internal fun dominantLayoutOrientation(orientations: List<TextOrientation?>): TextOrientation? {
    val counts = orientations
        .filterNotNull()
        .filter { it != TextOrientation.UNKNOWN }
        .groupingBy { it }
        .eachCount()
    return counts.maxWithOrNull(
        compareBy<Map.Entry<TextOrientation, Int>> { it.value }
            .thenBy { it.key.ordinal }
    )?.key
}
