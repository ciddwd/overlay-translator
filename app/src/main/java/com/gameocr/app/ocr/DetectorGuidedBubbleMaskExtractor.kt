package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Converts permissively licensed detector boxes into local bubble masks using image edges/colors.
 *
 * Every failed extraction produces an empty instance mask. Downstream association therefore falls
 * back safely instead of treating a detector rectangle as a verified bubble shape.
 */
internal object DetectorGuidedBubbleMaskExtractor {
    data class Decision(
        val detectionIndex: Int,
        val memberIndices: List<Int>,
        val diagnostic: MangaMaskDebugAnalyzer.BubbleDiagnostic,
    ) {
        val accepted: Boolean
            get() = diagnostic.accepted
    }

    data class Result(
        val detections: List<BubbleSegmentationPostprocessor.Detection>,
        val instanceMasks: List<BubbleSegmentationPostprocessor.InstanceMask>,
        val memberDetectionIndices: List<Int?>,
        val unionMask: BooleanArray,
        val decisions: List<Decision>,
        val durationMs: Long,
    ) {
        val acceptedCount: Int
            get() = decisions.count(Decision::accepted)
    }

    fun extract(
        width: Int,
        height: Int,
        argb: IntArray,
        polygons: List<MangaMaskDebugAnalyzer.Polygon>,
        boxDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
    ): Result {
        val startedAtNs = System.nanoTime()
        require(width > 0 && height > 0)
        require(argb.size == width * height)
        val modelDetections = boxDetections.map { detection ->
            BubbleSegmentationPostprocessor.Detection(
                confidence = detection.confidence,
                left = detection.left,
                top = detection.top,
                right = detection.right,
                bottom = detection.bottom,
                maskCoefficients = FloatArray(0),
            )
        }
        val membersByDetection = assignMembers(
            width = width,
            height = height,
            polygons = polygons,
            detections = boxDetections,
        )
        val memberDetectionIndices = MutableList<Int?>(polygons.size) { null }
        membersByDetection.forEach { (detectionIndex, memberIndices) ->
            memberIndices.forEach { memberIndex ->
                memberDetectionIndices[memberIndex] = detectionIndex
            }
        }
        val scratch = BooleanArray(width * height)
        val unionMask = BooleanArray(width * height)
        val decisions = ArrayList<Decision>(boxDetections.size)
        val instanceMasks = boxDetections.mapIndexed { detectionIndex, detection ->
            val members = membersByDetection[detectionIndex].orEmpty()
            val detectorBounds = detection.toIntRect(width, height)
            if (members.isEmpty()) {
                val diagnostic = MangaMaskDebugAnalyzer.BubbleDiagnostic(
                    roi = detectorBounds,
                    accepted = false,
                    confidence = 0f,
                    reason = "detector_no_ocr_members",
                    regionPixels = 0,
                )
                decisions += Decision(detectionIndex, emptyList(), diagnostic)
                return@mapIndexed emptyInstanceMask(detectorBounds)
            }
            val memberBounds = members.map { polygons[it].bounds }
            val contentBounds = union(memberBounds)
            val diagnostic = MangaMaskDebugAnalyzer.estimateBubbleInterior(
                width = width,
                height = height,
                argb = argb,
                polygons = polygons,
                bubble = MangaMaskDebugAnalyzer.BubbleInput(
                    contentBounds = contentBounds,
                    memberIndices = members,
                    searchBounds = paddedDetectorBounds(
                        detectorBounds = detectorBounds,
                        width = width,
                        height = height,
                    ),
                    useExactSearchBounds = true,
                ),
                output = scratch,
            )
            decisions += Decision(detectionIndex, members, diagnostic)
            if (!diagnostic.accepted) {
                clear(scratch, width, diagnostic.roi)
                return@mapIndexed emptyInstanceMask(detectorBounds)
            }
            val crop = cropMask(
                source = scratch,
                sourceWidth = width,
                sourceHeight = height,
                bounds = diagnostic.roi,
            )
            for (localY in 0 until crop.height) {
                val globalY = crop.top + localY
                for (localX in 0 until crop.width) {
                    if (!crop.pixels[localY * crop.width + localX]) continue
                    unionMask[globalY * width + crop.left + localX] = true
                }
            }
            clear(scratch, width, diagnostic.roi)
            crop
        }
        return Result(
            detections = modelDetections,
            instanceMasks = instanceMasks,
            memberDetectionIndices = memberDetectionIndices,
            unionMask = unionMask,
            decisions = decisions,
            durationMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
        )
    }

    private fun assignMembers(
        width: Int,
        height: Int,
        polygons: List<MangaMaskDebugAnalyzer.Polygon>,
        detections: List<MangaBubbleDetectionPostprocessor.Detection>,
    ): Map<Int, List<Int>> {
        val output = linkedMapOf<Int, MutableList<Int>>()
        polygons.forEachIndexed { memberIndex, polygon ->
            val member = clamp(polygon.bounds, width, height)
            if (member.width <= 0 || member.height <= 0) return@forEachIndexed
            val centerX = (member.left + member.right) / 2
            val centerY = (member.top + member.bottom) / 2
            val memberArea = member.width.toLong() * member.height
            val selected = detections.mapIndexedNotNull { detectionIndex, detection ->
                val model = detection.toIntRect(width, height)
                val intersection = intersectionArea(member, model)
                val coverage = intersection.toFloat() / memberArea.coerceAtLeast(1L)
                val centerInside =
                    centerX in model.left until model.right &&
                        centerY in model.top until model.bottom
                if (!centerInside && coverage < MIN_MEMBER_BOX_COVERAGE) {
                    return@mapIndexedNotNull null
                }
                MemberCandidate(
                    detectionIndex = detectionIndex,
                    score =
                        (if (centerInside) CENTER_INSIDE_WEIGHT else 0f) +
                            coverage * COVERAGE_WEIGHT +
                            detection.confidence * CONFIDENCE_WEIGHT,
                )
            }.maxByOrNull(MemberCandidate::score)
            selected?.let { candidate ->
                output.getOrPut(candidate.detectionIndex) { mutableListOf() } += memberIndex
            }
        }
        return output
    }

    private fun cropMask(
        source: BooleanArray,
        sourceWidth: Int,
        sourceHeight: Int,
        bounds: IntRect,
    ): BubbleSegmentationPostprocessor.InstanceMask {
        val crop = clamp(bounds, sourceWidth, sourceHeight)
        val pixels = BooleanArray(crop.width * crop.height)
        for (localY in 0 until crop.height) {
            val sourceOffset = (crop.top + localY) * sourceWidth + crop.left
            val destinationOffset = localY * crop.width
            source.copyInto(
                destination = pixels,
                destinationOffset = destinationOffset,
                startIndex = sourceOffset,
                endIndex = sourceOffset + crop.width,
            )
        }
        return BubbleSegmentationPostprocessor.InstanceMask(
            left = crop.left,
            top = crop.top,
            width = crop.width,
            height = crop.height,
            pixels = pixels,
        )
    }

    private fun clear(mask: BooleanArray, width: Int, bounds: IntRect) {
        val height = mask.size / width
        val clamped = clamp(bounds, width, height)
        for (y in clamped.top until clamped.bottom) {
            mask.fill(false, y * width + clamped.left, y * width + clamped.right)
        }
    }

    private fun emptyInstanceMask(
        bounds: IntRect,
    ): BubbleSegmentationPostprocessor.InstanceMask =
        BubbleSegmentationPostprocessor.InstanceMask(
            left = bounds.left,
            top = bounds.top,
            width = 0,
            height = 0,
            pixels = BooleanArray(0),
        )

    private fun MangaBubbleDetectionPostprocessor.Detection.toIntRect(
        width: Int,
        height: Int,
    ): IntRect = clamp(
        rect = IntRect(
            left = floor(left).toInt(),
            top = floor(top).toInt(),
            right = ceil(right).toInt(),
            bottom = ceil(bottom).toInt(),
        ),
        width = width,
        height = height,
    )

    private fun clamp(rect: IntRect, width: Int, height: Int): IntRect = IntRect(
        left = rect.left.coerceIn(0, width),
        top = rect.top.coerceIn(0, height),
        right = rect.right.coerceIn(0, width),
        bottom = rect.bottom.coerceIn(0, height),
    )

    private fun union(rects: List<IntRect>): IntRect {
        require(rects.isNotEmpty())
        return IntRect(
            left = rects.minOf(IntRect::left),
            top = rects.minOf(IntRect::top),
            right = rects.maxOf(IntRect::right),
            bottom = rects.maxOf(IntRect::bottom),
        )
    }

    private fun paddedDetectorBounds(
        detectorBounds: IntRect,
        width: Int,
        height: Int,
    ): IntRect {
        val margin = (
            minOf(detectorBounds.width, detectorBounds.height) *
                DETECTOR_ROI_MARGIN_RATIO
            ).roundToInt().coerceIn(MIN_DETECTOR_ROI_MARGIN_PX, MAX_DETECTOR_ROI_MARGIN_PX)
        return clamp(
            IntRect(
                left = detectorBounds.left - margin,
                top = detectorBounds.top - margin,
                right = detectorBounds.right + margin,
                bottom = detectorBounds.bottom + margin,
            ),
            width,
            height,
        )
    }

    private fun intersectionArea(first: IntRect, second: IntRect): Long {
        val width = minOf(first.right, second.right) - maxOf(first.left, second.left)
        val height = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        return width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0)
    }

    private data class MemberCandidate(
        val detectionIndex: Int,
        val score: Float,
    )

    private const val MIN_MEMBER_BOX_COVERAGE = 0.35f
    private const val CENTER_INSIDE_WEIGHT = 2f
    private const val COVERAGE_WEIGHT = 1f
    private const val CONFIDENCE_WEIGHT = 0.05f
    private const val DETECTOR_ROI_MARGIN_RATIO = 0.05f
    private const val MIN_DETECTOR_ROI_MARGIN_PX = 4
    private const val MAX_DETECTOR_ROI_MARGIN_PX = 24
}
