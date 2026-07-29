package com.gameocr.app.ocr

internal object MangaBubbleDetectionPostprocessor {
    enum class Kind(
        val modelLabel: Long,
    ) {
        BUBBLE(0L),
        TEXT_BUBBLE(1L),
        TEXT_FREE(2L),
        ;

        companion object {
            fun fromModelLabel(label: Long): Kind? = entries.firstOrNull {
                it.modelLabel == label
            }
        }
    }

    data class Detection(
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val kind: Kind = Kind.BUBBLE,
    )

    fun process(
        imageWidth: Int,
        imageHeight: Int,
        labels: LongArray,
        boxes: Array<FloatArray>,
        scores: FloatArray,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
        duplicateIouThreshold: Float = DEFAULT_DUPLICATE_IOU_THRESHOLD,
    ): List<Detection> = processAll(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        labels = labels,
        boxes = boxes,
        scores = scores,
        confidenceThreshold = confidenceThreshold,
        duplicateIouThreshold = duplicateIouThreshold,
    ).filter { detection -> detection.kind == Kind.BUBBLE }

    fun processAll(
        imageWidth: Int,
        imageHeight: Int,
        labels: LongArray,
        boxes: Array<FloatArray>,
        scores: FloatArray,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
        duplicateIouThreshold: Float = DEFAULT_DUPLICATE_IOU_THRESHOLD,
    ): List<Detection> {
        require(imageWidth > 0 && imageHeight > 0)
        require(confidenceThreshold in 0f..1f)
        require(duplicateIouThreshold > 0f && duplicateIouThreshold <= 1f)
        val count = minOf(labels.size, boxes.size, scores.size)
        val sorted = buildList {
            for (index in 0 until count) {
                val kind = Kind.fromModelLabel(labels[index]) ?: continue
                val confidence = scores[index]
                val box = boxes[index]
                if (!confidence.isFinite() || confidence < confidenceThreshold || box.size < 4) {
                    continue
                }
                val left = box[0].coerceIn(0f, imageWidth.toFloat())
                val top = box[1].coerceIn(0f, imageHeight.toFloat())
                val right = box[2].coerceIn(0f, imageWidth.toFloat())
                val bottom = box[3].coerceIn(0f, imageHeight.toFloat())
                if (
                    !left.isFinite() ||
                    !top.isFinite() ||
                    !right.isFinite() ||
                    !bottom.isFinite() ||
                    right - left < MIN_BOX_SIDE_PX ||
                    bottom - top < MIN_BOX_SIDE_PX
                ) {
                    continue
                }
                add(
                    Detection(
                        confidence = confidence,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        kind = kind,
                    )
                )
            }
        }.sortedByDescending(Detection::confidence)
        return collapseNearDuplicates(sorted, duplicateIouThreshold)
    }

    private fun collapseNearDuplicates(
        detections: List<Detection>,
        duplicateIouThreshold: Float,
    ): List<Detection> = buildList {
        detections.forEach { candidate ->
            if (
                none { kept ->
                    candidate.kind == kept.kind &&
                        intersectionOverUnion(candidate, kept) >= duplicateIouThreshold
                }
            ) {
                add(candidate)
            }
        }
    }

    private fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val intersectionWidth =
            (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0f)
        val intersectionHeight =
            (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f)
        val intersectionArea = intersectionWidth * intersectionHeight
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        val unionArea = firstArea + secondArea - intersectionArea
        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    const val DEFAULT_CONFIDENCE_THRESHOLD: Float = 0.30f
    const val DEFAULT_DUPLICATE_IOU_THRESHOLD: Float = 0.90f
    private const val MIN_BOX_SIDE_PX: Float = 4f
}
