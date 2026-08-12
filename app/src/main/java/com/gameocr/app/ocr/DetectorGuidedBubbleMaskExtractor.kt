package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Converts permissively licensed detector boxes into local bubble masks using image edges/colors.
 *
 * Boundary leaks can fall back to a conservative ellipse inside a detector box for association and
 * text-repair routing. That synthetic mask is explicitly marked approximate and cannot define a
 * final shape patch. All other failed extractions still produce an empty instance mask.
 */
internal object DetectorGuidedBubbleMaskExtractor {
    data class Timing(
        val totalUs: Long = 0L,
        val assignmentUs: Long = 0L,
        val estimateTotalUs: Long = 0L,
        val backgroundUs: Long = 0L,
        val luminanceUs: Long = 0L,
        val candidateBuildUs: Long = 0L,
        val seedUs: Long = 0L,
        val floodUs: Long = 0L,
        val edgeUs: Long = 0L,
        val fillUs: Long = 0L,
        val coverageAndCopyUs: Long = 0L,
        val ellipseFallbackUs: Long = 0L,
        val maskIoUs: Long = 0L,
        val otherUs: Long = 0L,
        val estimateCalls: Int = 0,
        val ellipseFallbackCalls: Int = 0,
    )

    data class Decision(
        val detectionIndex: Int,
        val memberIndices: List<Int>,
        val diagnostic: MangaMaskDebugAnalyzer.BubbleDiagnostic,
    ) {
        val accepted: Boolean
            get() = diagnostic.accepted

        val shapeMaskQuality: BubbleShapeMaskQuality
            get() = BubbleShapeMaskQuality.fromDetectorDecision(
                accepted = accepted,
                reason = diagnostic.reason,
            )
    }

    data class Result(
        val detections: List<BubbleSegmentationPostprocessor.Detection>,
        val instanceMasks: List<BubbleSegmentationPostprocessor.InstanceMask>,
        val memberDetectionIndices: List<Int?>,
        val unionMask: BooleanArray,
        val decisions: List<Decision>,
        val durationMs: Long,
        val timing: Timing = Timing(),
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
        val assignmentStartedAtNs = System.nanoTime()
        val membersByDetection = assignMembers(
            width = width,
            height = height,
            polygons = polygons,
            detections = boxDetections,
        )
        val assignmentNs = System.nanoTime() - assignmentStartedAtNs
        val memberDetectionIndices = MutableList<Int?>(polygons.size) { null }
        membersByDetection.forEach { (detectionIndex, memberIndices) ->
            memberIndices.forEach { memberIndex ->
                memberDetectionIndices[memberIndex] = detectionIndex
            }
        }
        val scratch = BooleanArray(width * height)
        val unionMask = BooleanArray(width * height)
        val decisions = ArrayList<Decision>(boxDetections.size)
        val bubbleTiming = MangaMaskDebugAnalyzer.BubbleTiming()
        val analyzerWorkspace = MangaMaskDebugAnalyzer.Workspace()
        var estimateNs = 0L
        var ellipseFallbackNs = 0L
        var maskIoNs = 0L
        var estimateCalls = 0
        var ellipseFallbackCalls = 0
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
            val estimateStartedAtNs = System.nanoTime()
            estimateCalls++
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
                    retrySearchBounds = paddedDetectorBounds(
                        detectorBounds = detectorBounds,
                        width = width,
                        height = height,
                        marginMultiplier = DETECTOR_ROI_RETRY_MARGIN_MULTIPLIER,
                    ),
                    useExactSearchBounds = true,
                ),
                output = scratch,
                timing = bubbleTiming,
                workspace = analyzerWorkspace,
            )
            estimateNs += System.nanoTime() - estimateStartedAtNs
            if (!diagnostic.accepted) {
                val clearStartedAtNs = System.nanoTime()
                clear(scratch, width, diagnostic.roi)
                maskIoNs += System.nanoTime() - clearStartedAtNs
                val ellipseStartedAtNs = System.nanoTime()
                ellipseFallbackCalls++
                val ellipseFallback = buildEllipseFallback(
                    detectorBounds = detectorBounds,
                    polygons = members.map(polygons::get),
                    failedDiagnostic = diagnostic,
                )
                ellipseFallbackNs += System.nanoTime() - ellipseStartedAtNs
                if (ellipseFallback != null) {
                    val mergeStartedAtNs = System.nanoTime()
                    mergeIntoUnion(
                        instanceMask = ellipseFallback.instanceMask,
                        unionMask = unionMask,
                        unionWidth = width,
                    )
                    maskIoNs += System.nanoTime() - mergeStartedAtNs
                    decisions += Decision(
                        detectionIndex = detectionIndex,
                        memberIndices = members,
                        diagnostic = diagnostic.copy(
                            roi = detectorBounds,
                            accepted = true,
                            confidence = detection.confidence * ellipseFallback.memberCoverage,
                            reason = "accepted_ellipse_fallback",
                            regionPixels = ellipseFallback.pixelCount,
                            memberCoverage = ellipseFallback.memberCoverage,
                        ),
                    )
                    return@mapIndexed ellipseFallback.instanceMask
                }
                decisions += Decision(detectionIndex, members, diagnostic)
                return@mapIndexed emptyInstanceMask(detectorBounds)
            }
            decisions += Decision(detectionIndex, members, diagnostic)
            val materializeStartedAtNs = System.nanoTime()
            val crop = cropMask(
                source = scratch,
                sourceWidth = width,
                sourceHeight = height,
                bounds = diagnostic.roi,
            )
            mergeIntoUnion(crop, unionMask, width)
            clear(scratch, width, diagnostic.roi)
            maskIoNs += System.nanoTime() - materializeStartedAtNs
            crop
        }
        val totalNs = System.nanoTime() - startedAtNs
        val otherNs = (
            totalNs - assignmentNs - estimateNs - ellipseFallbackNs - maskIoNs
            ).coerceAtLeast(0L)
        return Result(
            detections = modelDetections,
            instanceMasks = instanceMasks,
            memberDetectionIndices = memberDetectionIndices,
            unionMask = unionMask,
            decisions = decisions,
            durationMs = totalNs / 1_000_000L,
            timing = Timing(
                totalUs = totalNs / 1_000L,
                assignmentUs = assignmentNs / 1_000L,
                estimateTotalUs = estimateNs / 1_000L,
                backgroundUs = bubbleTiming.backgroundNs / 1_000L,
                luminanceUs = bubbleTiming.luminanceNs / 1_000L,
                candidateBuildUs = bubbleTiming.candidateBuildNs / 1_000L,
                seedUs = bubbleTiming.seedNs / 1_000L,
                floodUs = bubbleTiming.floodNs / 1_000L,
                edgeUs = bubbleTiming.edgeNs / 1_000L,
                fillUs = bubbleTiming.fillNs / 1_000L,
                coverageAndCopyUs = bubbleTiming.coverageAndCopyNs / 1_000L,
                ellipseFallbackUs = ellipseFallbackNs / 1_000L,
                maskIoUs = maskIoNs / 1_000L,
                otherUs = otherNs / 1_000L,
                estimateCalls = estimateCalls,
                ellipseFallbackCalls = ellipseFallbackCalls,
            ),
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

    private fun buildEllipseFallback(
        detectorBounds: IntRect,
        polygons: List<MangaMaskDebugAnalyzer.Polygon>,
        failedDiagnostic: MangaMaskDebugAnalyzer.BubbleDiagnostic,
    ): EllipseFallback? {
        if (failedDiagnostic.reason !in ELLIPSE_FALLBACK_REASONS) return null
        if (detectorBounds.width <= 0 || detectorBounds.height <= 0) return null

        val inset = ELLIPSE_INSET_PX.coerceAtMost(
            (minOf(detectorBounds.width, detectorBounds.height) - 2)
                .coerceAtLeast(0) / 2,
        )
        val centerX = (detectorBounds.left + detectorBounds.right) / 2f
        val centerY = (detectorBounds.top + detectorBounds.bottom) / 2f
        val radiusX = ((detectorBounds.width - inset * 2) / 2f).coerceAtLeast(1f)
        val radiusY = ((detectorBounds.height - inset * 2) / 2f).coerceAtLeast(1f)
        val pixels = BooleanArray(detectorBounds.width * detectorBounds.height)
        var pixelCount = 0
        for (localY in 0 until detectorBounds.height) {
            val normalizedY =
                (detectorBounds.top + localY + PIXEL_CENTER_OFFSET - centerY) / radiusY
            for (localX in 0 until detectorBounds.width) {
                val normalizedX =
                    (detectorBounds.left + localX + PIXEL_CENTER_OFFSET - centerX) / radiusX
                val inside = normalizedX * normalizedX + normalizedY * normalizedY <= 1f
                pixels[localY * detectorBounds.width + localX] = inside
                if (inside) pixelCount++
            }
        }
        val instanceMask = BubbleSegmentationPostprocessor.InstanceMask(
            left = detectorBounds.left,
            top = detectorBounds.top,
            width = detectorBounds.width,
            height = detectorBounds.height,
            pixels = pixels,
        )
        val memberCoverage = memberMaskCoverage(instanceMask, polygons)
        if (memberCoverage < MIN_ELLIPSE_MEMBER_COVERAGE) return null
        return EllipseFallback(instanceMask, memberCoverage, pixelCount)
    }

    private fun memberMaskCoverage(
        mask: BubbleSegmentationPostprocessor.InstanceMask,
        polygons: List<MangaMaskDebugAnalyzer.Polygon>,
    ): Float {
        var memberPixels = 0
        var coveredPixels = 0
        polygons.forEach { polygon ->
            val bounds = polygon.bounds
            val left = maxOf(bounds.left, mask.left)
            val top = maxOf(bounds.top, mask.top)
            val right = minOf(bounds.right, mask.left + mask.width)
            val bottom = minOf(bounds.bottom, mask.top + mask.height)
            for (y in top until bottom) {
                for (x in left until right) {
                    if (!pointInPolygon(
                            x = x + PIXEL_CENTER_OFFSET,
                            y = y + PIXEL_CENTER_OFFSET,
                            points = polygon.points,
                        )
                    ) {
                        continue
                    }
                    memberPixels++
                    if (mask.contains(x, y)) coveredPixels++
                }
            }
        }
        return if (memberPixels == 0) {
            0f
        } else {
            coveredPixels.toFloat() / memberPixels
        }
    }

    private fun pointInPolygon(
        x: Float,
        y: Float,
        points: List<MangaMaskDebugAnalyzer.Point>,
    ): Boolean {
        var inside = false
        var previous = points.last()
        points.forEach { current ->
            if (
                (current.y > y) != (previous.y > y) &&
                x < (previous.x - current.x) * (y - current.y) /
                    (previous.y - current.y + MIN_POLYGON_DIVISOR) + current.x
            ) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun mergeIntoUnion(
        instanceMask: BubbleSegmentationPostprocessor.InstanceMask,
        unionMask: BooleanArray,
        unionWidth: Int,
    ) {
        for (localY in 0 until instanceMask.height) {
            val globalY = instanceMask.top + localY
            for (localX in 0 until instanceMask.width) {
                if (!instanceMask.pixels[localY * instanceMask.width + localX]) continue
                unionMask[globalY * unionWidth + instanceMask.left + localX] = true
            }
        }
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
        marginMultiplier: Int = 1,
    ): IntRect {
        val baseMargin = (
            minOf(detectorBounds.width, detectorBounds.height) *
                DETECTOR_ROI_MARGIN_RATIO
            ).roundToInt().coerceIn(MIN_DETECTOR_ROI_MARGIN_PX, MAX_DETECTOR_ROI_MARGIN_PX)
        val margin = (baseMargin * marginMultiplier)
            .coerceAtMost(MAX_DETECTOR_ROI_RETRY_MARGIN_PX)
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

    private data class EllipseFallback(
        val instanceMask: BubbleSegmentationPostprocessor.InstanceMask,
        val memberCoverage: Float,
        val pixelCount: Int,
    )

    private val ELLIPSE_FALLBACK_REASONS = setOf(
        "region_leaked_to_roi",
        "region_too_large",
        "edge_region_leaked",
        "edge_region_too_large",
    )
    private const val MIN_MEMBER_BOX_COVERAGE = 0.35f
    private const val MIN_ELLIPSE_MEMBER_COVERAGE = 0.72f
    private const val ELLIPSE_INSET_PX = 7
    private const val PIXEL_CENTER_OFFSET = 0.5f
    private const val MIN_POLYGON_DIVISOR = 0.000001f
    private const val CENTER_INSIDE_WEIGHT = 2f
    private const val COVERAGE_WEIGHT = 1f
    private const val CONFIDENCE_WEIGHT = 0.05f
    private const val DETECTOR_ROI_MARGIN_RATIO = 0.05f
    private const val MIN_DETECTOR_ROI_MARGIN_PX = 4
    private const val MAX_DETECTOR_ROI_MARGIN_PX = 24
    private const val DETECTOR_ROI_RETRY_MARGIN_MULTIPLIER = 2
    private const val MAX_DETECTOR_ROI_RETRY_MARGIN_PX = 48
}
