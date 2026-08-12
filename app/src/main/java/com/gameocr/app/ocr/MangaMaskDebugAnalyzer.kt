package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Kotlin analysis used by the manga mask prototype.
 *
 * The output is diagnostic-only. Production overlay rendering must not consume it until the
 * masks have been validated on representative device captures.
 */
internal object MangaMaskDebugAnalyzer {

    data class Point(val x: Float, val y: Float)

    data class Polygon(val points: List<Point>) {
        init {
            require(points.size >= 3)
        }

        val bounds: IntRect
            get() = IntRect(
                left = floor(points.minOf { it.x }).toInt(),
                top = floor(points.minOf { it.y }).toInt(),
                right = ceil(points.maxOf { it.x }).toInt(),
                bottom = ceil(points.maxOf { it.y }).toInt(),
            )
    }

    data class BubbleInput(
        val contentBounds: IntRect,
        val memberIndices: List<Int>,
        val searchBounds: IntRect? = null,
        val retrySearchBounds: IntRect? = null,
        /**
         * Detector boxes already provide bounded local work areas. When true, use the supplied
         * crops instead of deriving the wider OCR-only heuristic crops. [retrySearchBounds] is
         * attempted only when the first crop leaks at its boundary.
         */
        val useExactSearchBounds: Boolean = false,
    )

    data class BubbleDiagnostic(
        val roi: IntRect,
        val accepted: Boolean,
        val confidence: Float,
        val reason: String,
        val regionPixels: Int,
        val attempts: Int = 1,
        val memberCoverage: Float = 0f,
    )

    data class Analysis(
        val textEraseMask: BooleanArray,
        val bubbleInteriorMask: BooleanArray,
        val bubbles: List<BubbleDiagnostic>,
    ) {
        val acceptedBubbleCount: Int
            get() = bubbles.count { it.accepted }
    }

    internal data class EdgeTestResult(
        val mask: BooleanArray?,
        val regionPixels: Int,
        val reason: String,
    )

    internal class BubbleTiming {
        var backgroundNs: Long = 0L
        var luminanceNs: Long = 0L
        var candidateBuildNs: Long = 0L
        var seedNs: Long = 0L
        var floodNs: Long = 0L
        var edgeNs: Long = 0L
        var fillNs: Long = 0L
        var coverageAndCopyNs: Long = 0L
    }

    internal class Workspace {
        internal val background = BubbleBackgroundStatistics.Scratch()
    }

    fun analyze(
        width: Int,
        height: Int,
        argb: IntArray,
        probabilityTextMask: BooleanArray,
        polygons: List<Polygon>,
        bubbles: List<BubbleInput>,
    ): Analysis {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
        require(probabilityTextMask.size == width * height)

        val bubbleInteriorMask = BooleanArray(width * height)
        val workspace = Workspace()
        val diagnostics = bubbles.map { bubble ->
            estimateBubble(
                width = width,
                height = height,
                argb = argb,
                polygons = polygons,
                bubble = bubble,
                output = bubbleInteriorMask,
                workspace = workspace,
            )
        }
        val polygonMask = rasterizePolygons(width, height, polygons)
        val textEraseMask = buildTextEraseMask(
            width = width,
            height = height,
            argb = argb,
            probabilityTextMask = probabilityTextMask,
            polygonMask = polygonMask,
            polygons = polygons,
        )
        return Analysis(
            textEraseMask = textEraseMask,
            bubbleInteriorMask = bubbleInteriorMask,
            bubbles = diagnostics,
        )
    }

    internal fun analyzeEdgeRegionForTest(
        width: Int,
        height: Int,
        argb: IntArray,
        polygons: List<Polygon>,
        backgroundLuminance: Int,
        lowBackgroundLuminance: Int,
    ): EdgeTestResult {
        val attempt = findEdgeBoundedRegion(
            argb = argb,
            imageWidth = width,
            roi = IntRect(0, 0, width, height),
            members = polygons,
            background = BackgroundColor(
                red = backgroundLuminance,
                green = backgroundLuminance,
                blue = backgroundLuminance,
                luminance = backgroundLuminance,
                lowLuminance = lowBackgroundLuminance,
            ),
        )
        return EdgeTestResult(
            mask = attempt.region,
            regionPixels = attempt.regionPixels,
            reason = attempt.reason,
        )
    }

    internal fun estimateBubbleInterior(
        width: Int,
        height: Int,
        argb: IntArray,
        polygons: List<Polygon>,
        bubble: BubbleInput,
        output: BooleanArray,
        timing: BubbleTiming? = null,
        workspace: Workspace = Workspace(),
    ): BubbleDiagnostic {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
        require(output.size == width * height)
        return estimateBubble(
            width = width,
            height = height,
            argb = argb,
            polygons = polygons,
            bubble = bubble,
            output = output,
            timing = timing,
            workspace = workspace,
        )
    }

    internal fun rasterizePolygons(
        width: Int,
        height: Int,
        polygons: List<Polygon>,
    ): BooleanArray {
        val output = BooleanArray(width * height)
        polygons.forEach { polygon ->
            val bounds = clamp(polygon.bounds, width, height)
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    if (pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)) {
                        output[y * width + x] = true
                    }
                }
            }
        }
        return output
    }

    private fun buildTextEraseMask(
        width: Int,
        height: Int,
        argb: IntArray,
        probabilityTextMask: BooleanArray,
        polygonMask: BooleanArray,
        polygons: List<Polygon>,
    ): BooleanArray {
        val core = BooleanArray(width * height)
        polygons.forEach { polygon ->
            val bounds = clamp(polygon.bounds, width, height)
            if (bounds.width <= 0 || bounds.height <= 0) return@forEach
            val samples = mutableListOf<Int>()
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    val index = y * width + x
                    if (
                        probabilityTextMask[index] &&
                        pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)
                    ) {
                        samples += luminance(argb[index])
                    }
                }
            }
            val surroundingColors = surroundingColorSamples(
                width = width,
                height = height,
                argb = argb,
                probabilityTextMask = probabilityTextMask,
                polygon = polygon,
            )
            val selection = foregroundSelection(
                samples = samples,
                surroundingSamples = surroundingColors.map(::luminance),
            )
            val localSize = bounds.width * bounds.height
            val localArgb = IntArray(localSize)
            val localSupport = BooleanArray(localSize)
            val localStrong = BooleanArray(localSize)
            for (y in bounds.top until bounds.bottom) {
                for (x in bounds.left until bounds.right) {
                    val index = y * width + x
                    val localIndex = (y - bounds.top) * bounds.width + (x - bounds.left)
                    localArgb[localIndex] = argb[index]
                    val inside = pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)
                    localSupport[localIndex] = inside
                    localStrong[localIndex] = inside &&
                        probabilityTextMask[index] &&
                        (selection?.contains(luminance(argb[index])) ?: true)
                }
            }
            val completed = if (selection != null) {
                TextForegroundMaskCompleter.complete(
                    width = bounds.width,
                    height = bounds.height,
                    argb = localArgb,
                    strongMask = localStrong,
                    supportMask = localSupport,
                    backgroundSamples = surroundingColors.toIntArray(),
                ).mask
            } else {
                localStrong
            }
            for (localIndex in completed.indices) {
                if (!completed[localIndex]) continue
                val localX = localIndex % bounds.width
                val localY = localIndex / bounds.width
                core[(bounds.top + localY) * width + bounds.left + localX] = true
            }
        }
        val dilated = BooleanArray(core.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!core[index]) continue
                for (dy in -TEXT_MASK_DILATION_PX..TEXT_MASK_DILATION_PX) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -TEXT_MASK_DILATION_PX..TEXT_MASK_DILATION_PX) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val next = ny * width + nx
                        if (polygonMask[next]) dilated[next] = true
                    }
                }
            }
        }
        return dilated
    }

    private data class ForegroundSelection(
        val threshold: Int,
        val darkForeground: Boolean,
    ) {
        fun contains(value: Int): Boolean =
            if (darkForeground) value <= threshold else value > threshold
    }

    /**
     * DBNet predicts a dense text region, not individual glyph pixels. Split the local luminance
     * histogram and keep the smaller side, which is normally dark ink on a light balloon (or light
     * ink on a dark balloon). Low-contrast regions keep the DBNet mask as a conservative fallback.
     */
    private fun foregroundSelection(
        samples: List<Int>,
        surroundingSamples: List<Int>,
    ): ForegroundSelection? {
        if (samples.size < MIN_TEXT_LUMINANCE_SAMPLES) return null
        val sorted = samples.sorted()
        val low = sorted[(sorted.lastIndex * 0.1f).roundToInt()]
        val high = sorted[(sorted.lastIndex * 0.9f).roundToInt()]
        if (high - low < MIN_TEXT_LUMINANCE_CONTRAST) return null

        val histogram = IntArray(256)
        samples.forEach { histogram[it.coerceIn(0, 255)]++ }
        val total = samples.size
        var totalWeighted = 0L
        histogram.forEachIndexed { value, count -> totalWeighted += value.toLong() * count }
        var backgroundWeight = 0
        var backgroundWeighted = 0L
        var bestVariance = -1.0
        var bestThreshold = (low + high) / 2
        for (threshold in 0 until 255) {
            val count = histogram[threshold]
            backgroundWeight += count
            backgroundWeighted += threshold.toLong() * count
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break
            val backgroundMean = backgroundWeighted.toDouble() / backgroundWeight
            val foregroundMean = (totalWeighted - backgroundWeighted).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = threshold
            }
        }
        val darkCount = samples.count { it <= bestThreshold }
        val lightCount = total - darkCount
        val surroundingBackground = surroundingSamples
            .takeIf { it.size >= MIN_SURROUNDING_LUMINANCE_SAMPLES }
            ?.sorted()
            ?.let { it[it.size / 2] }
        val darkForeground = if (
            surroundingBackground != null &&
            darkCount > 0 &&
            lightCount > 0
        ) {
            val darkWeighted = samples.sumOf { value ->
                if (value <= bestThreshold) value.toLong() else 0L
            }
            val darkMean = darkWeighted.toDouble() / darkCount
            val lightMean = (totalWeighted - darkWeighted).toDouble() / lightCount
            val darkDistance = abs(darkMean - surroundingBackground)
            val lightDistance = abs(lightMean - surroundingBackground)
            when {
                darkDistance > lightDistance -> true
                lightDistance > darkDistance -> false
                else -> darkCount <= lightCount
            }
        } else {
            darkCount <= lightCount
        }
        return ForegroundSelection(
            threshold = bestThreshold,
            darkForeground = darkForeground,
        )
    }

    private fun surroundingColorSamples(
        width: Int,
        height: Int,
        argb: IntArray,
        probabilityTextMask: BooleanArray,
        polygon: Polygon,
    ): List<Int> {
        val bounds = clamp(polygon.bounds, width, height)
        val margin = (minOf(bounds.width, bounds.height) * SURROUNDING_SAMPLE_MARGIN_RATIO)
            .roundToInt()
            .coerceIn(MIN_SURROUNDING_SAMPLE_MARGIN_PX, MAX_SURROUNDING_SAMPLE_MARGIN_PX)
        val expanded = clamp(
            IntRect(
                left = bounds.left - margin,
                top = bounds.top - margin,
                right = bounds.right + margin,
                bottom = bounds.bottom + margin,
            ),
            width,
            height,
        )
        val samples = mutableListOf<Int>()
        for (y in expanded.top until expanded.bottom) {
            for (x in expanded.left until expanded.right) {
                val index = y * width + x
                if (probabilityTextMask[index]) continue
                if (pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)) continue
                samples += argb[index]
            }
        }
        return samples
    }

    private fun estimateBubble(
        width: Int,
        height: Int,
        argb: IntArray,
        polygons: List<Polygon>,
        bubble: BubbleInput,
        output: BooleanArray,
        timing: BubbleTiming? = null,
        workspace: Workspace = Workspace(),
    ): BubbleDiagnostic {
        val members = bubble.memberIndices.mapNotNull(polygons::getOrNull)
        if (members.isEmpty()) {
            return rejected(clamp(bubble.contentBounds, width, height), "no_members")
        }

        val content = clamp(bubble.contentBounds, width, height)
        if (content.width <= 0 || content.height <= 0) {
            return rejected(content, "empty_content")
        }
        val minimumLongSide = max(
            MIN_CONTENT_LONG_SIDE_PX,
            minOf(width, height) / MIN_CONTENT_LONG_SIDE_SCREEN_DIVISOR,
        )
        val minimumContentArea = max(
            MIN_CONTENT_AREA_PX,
            width.toLong() * height / MIN_CONTENT_AREA_SCREEN_DIVISOR,
        )
        if (
            max(content.width, content.height) < minimumLongSide ||
            content.width.toLong() * content.height < minimumContentArea
        ) {
            return rejected(content, "content_too_small")
        }

        val candidateRois = if (bubble.useExactSearchBounds && bubble.searchBounds != null) {
            listOfNotNull(bubble.searchBounds, bubble.retrySearchBounds)
                .map { bounds -> clamp(bounds, width, height) }
                .distinct()
        } else {
            candidateRois(
                content = bubble.searchBounds ?: content,
                width = width,
                height = height,
            )
        }
        var preparedSeedCandidates: List<MangaSeedSearchOrder>? = null
        var lastReason = "region_leaked_to_roi"
        var lastPixels = 0
        var lastRoi = candidateRois.first()
        for ((attemptIndex, roi) in candidateRois.withIndex()) {
            val attempts = attemptIndex + 1
            lastRoi = roi
            if (roi.width < MIN_ROI_SIDE_PX || roi.height < MIN_ROI_SIDE_PX) {
                return rejected(roi, "roi_too_small", attempts = attempts)
            }

            val backgroundStartedAtNs = System.nanoTime()
            val background = estimateBackgroundAroundText(
                argb = argb,
                width = width,
                roi = roi,
                members = members,
                scratch = workspace.background,
            )
            timing?.let { it.backgroundNs += System.nanoTime() - backgroundStartedAtNs }
            if (background == null) {
                return rejected(roi, "no_light_background", attempts = attempts)
            }
            if (background.luminance < MIN_BACKGROUND_LUMINANCE) {
                return rejected(roi, "background_too_dark", attempts = attempts)
            }
            val seedCandidates = preparedSeedCandidates ?: run {
                val seedCandidateStartedAtNs = System.nanoTime()
                members.map { polygon ->
                    buildSeedCandidates(
                        polygon = polygon,
                        imageWidth = width,
                        imageHeight = height,
                    )
                }.also { prepared ->
                    preparedSeedCandidates = prepared
                    timing?.let {
                        it.seedNs += System.nanoTime() - seedCandidateStartedAtNs
                    }
                }
            }

            val luminanceStartedAtNs = System.nanoTime()
            val roiFeatures = extractRoiFeatures(
                argb = argb,
                imageWidth = width,
                roi = roi,
                background = background,
            )
            timing?.let { it.luminanceNs += System.nanoTime() - luminanceStartedAtNs }
            val candidate = BooleanArray(roi.width * roi.height)
            val workQueue = IntArray(candidate.size)
            var bestRegion: BooleanArray? = null
            var bestRegionPixels = 0
            var bestProfileIndex = 0
            var retryWithExpandedRoi = false
            var invalidatedByLeak = false
            for ((profileIndex, profile) in CANDIDATE_PROFILES.withIndex()) {
                val candidateStartedAtNs = System.nanoTime()
                val maximumColorDistanceSquared =
                    profile.maxColorDistance * profile.maxColorDistance
                val minimumLuminance = minimumCandidateLuminance(background, profile)
                for (localIndex in candidate.indices) {
                    candidate[localIndex] =
                        roiFeatures.colorDistanceSquared[localIndex].toFloat() <=
                        maximumColorDistanceSquared &&
                        roiFeatures.luminances[localIndex] >= minimumLuminance
                }
                timing?.let { it.candidateBuildNs += System.nanoTime() - candidateStartedAtNs }

                val seedStartedAtNs = System.nanoTime()
                val seeds = seedCandidates.mapNotNull { it.nearest(candidate, roi) }.distinct()
                timing?.let { it.seedNs += System.nanoTime() - seedStartedAtNs }
                if (seeds.isEmpty()) {
                    lastReason = "no_background_seed"
                    continue
                }

                val floodStartedAtNs = System.nanoTime()
                val flood = floodCandidateRegion(
                    candidate = candidate,
                    width = roi.width,
                    height = roi.height,
                    seeds = seeds,
                    queue = workQueue,
                )
                timing?.let { it.floodNs += System.nanoTime() - floodStartedAtNs }
                val region = flood.mask
                val rawRegionPixels = flood.pixelCount
                lastPixels = rawRegionPixels
                if (rawRegionPixels < max(MIN_REGION_PIXELS, content.width * content.height / 5)) {
                    lastReason = "region_too_small"
                    continue
                }

                val leaked = flood.touchesBoundary
                val tooLarge = rawRegionPixels >=
                    (roi.width * roi.height * MAX_REGION_ROI_RATIO).roundToInt()
                if (leaked || tooLarge) {
                    lastReason = if (leaked) "region_leaked_to_roi" else "region_too_large"
                    retryWithExpandedRoi = attemptIndex < candidateRois.lastIndex
                    invalidatedByLeak = true
                    break
                }
                if (rawRegionPixels > bestRegionPixels) {
                    bestRegion = region
                    bestRegionPixels = rawRegionPixels
                    bestProfileIndex = profileIndex
                }
            }

            val edgeStartedAtNs = System.nanoTime()
            val edgeAttempt = findEdgeBoundedRegion(
                argb = argb,
                imageWidth = width,
                roi = roi,
                members = members,
                background = background,
                luminances = roiFeatures.luminances,
                queue = workQueue,
                seedCandidates = seedCandidates,
            )
            timing?.let { it.edgeNs += System.nanoTime() - edgeStartedAtNs }
            if (edgeAttempt.regionPixels > lastPixels) {
                lastPixels = edgeAttempt.regionPixels
                lastReason = edgeAttempt.reason
            }
            val colorRegion = if (invalidatedByLeak) null else bestRegion
            val useEdgeRegion = edgeAttempt.region != null && (
                colorRegion == null ||
                    edgeAttempt.regionPixels >
                    (bestRegionPixels * EDGE_PREFERRED_SIZE_RATIO).roundToInt()
                )
            val selectedRegion = if (useEdgeRegion) edgeAttempt.region else colorRegion
            if (selectedRegion == null) {
                if (
                    attemptIndex < candidateRois.lastIndex &&
                    (retryWithExpandedRoi || edgeAttempt.canRetryWithExpandedRoi)
                ) {
                    continue
                }
                return rejected(roi, lastReason, lastPixels, attempts)
            }

            val fillStartedAtNs = System.nanoTime()
            val filledRegion = fillEnclosedHoles(
                region = selectedRegion,
                width = roi.width,
                height = roi.height,
                queue = workQueue,
            )
            timing?.let { it.fillNs += System.nanoTime() - fillStartedAtNs }
            val filled = filledRegion.mask
            val regionPixels = filledRegion.pixelCount
            val coverageStartedAtNs = System.nanoTime()
            val memberCoverage = memberRegionCoverage(
                region = filled,
                roi = roi,
                members = members,
            )
            if (memberCoverage < MIN_MEMBER_REGION_COVERAGE) {
                timing?.let {
                    it.coverageAndCopyNs += System.nanoTime() - coverageStartedAtNs
                }
                return BubbleDiagnostic(
                    roi = roi,
                    accepted = false,
                    confidence = 0f,
                    reason = "member_coverage_low",
                    regionPixels = regionPixels,
                    attempts = attempts,
                    memberCoverage = memberCoverage,
                )
            }
            val coverage = regionPixels.toFloat() / (roi.width * roi.height).coerceAtLeast(1)
            val confidence = (
                1f -
                    ((coverage - IDEAL_REGION_ROI_RATIO).coerceAtLeast(0f) /
                        (MAX_REGION_ROI_RATIO - IDEAL_REGION_ROI_RATIO))
                ).coerceIn(0.1f, 1f)

            for (localY in 0 until roi.height) {
                val globalY = roi.top + localY
                val localOffset = localY * roi.width
                val globalOffset = globalY * width + roi.left
                for (localX in 0 until roi.width) {
                    if (filled[localOffset + localX]) {
                        output[globalOffset + localX] = true
                    }
                }
            }
            timing?.let { it.coverageAndCopyNs += System.nanoTime() - coverageStartedAtNs }
            return BubbleDiagnostic(
                roi = roi,
                accepted = true,
                confidence = confidence,
                reason = if (useEdgeRegion) {
                    if (attempts > 1) {
                        "accepted_edge_after_roi_expand"
                    } else {
                        "accepted_edge"
                    }
                } else {
                    acceptedReason(attempts, bestProfileIndex)
                },
                regionPixels = regionPixels,
                attempts = attempts,
                memberCoverage = memberCoverage,
            )
        }
        return rejected(
            roi = lastRoi,
            reason = lastReason,
            regionPixels = lastPixels,
            attempts = candidateRois.size,
        )
    }

    /**
     * A narrow vertical OCR box can sit inside a much wider speech balloon. Retry once with a
     * larger observation window when the color region reaches the first ROI edge. A genuinely
     * open region keeps leaking after expansion, while a closed balloon becomes bounded.
     */
    private fun candidateRois(
        content: IntRect,
        width: Int,
        height: Int,
    ): List<IntRect> {
        val maximumContentSpan = max(content.width, content.height)
        val baseMargin = (maximumContentSpan * ROI_MARGIN_RATIO).roundToInt()
            .coerceIn(MIN_ROI_MARGIN_PX, MAX_ROI_MARGIN_PX)
        val expandedMargin = (baseMargin * ROI_RETRY_MARGIN_MULTIPLIER).roundToInt()
            .coerceAtMost(MAX_RETRY_ROI_MARGIN_PX)
        return listOf(baseMargin, expandedMargin)
            .distinct()
            .map { margin ->
                IntRect(
                    left = (content.left - margin).coerceAtLeast(0),
                    top = (content.top - margin).coerceAtLeast(0),
                    right = (content.right + margin).coerceAtMost(width),
                    bottom = (content.bottom + margin).coerceAtMost(height),
                )
            }
            .distinct()
    }

    private data class BackgroundColor(
        val red: Int,
        val green: Int,
        val blue: Int,
        val luminance: Int,
        val lowLuminance: Int,
    )

    private data class RoiFeatures(
        val luminances: IntArray,
        val colorDistanceSquared: IntArray,
    )

    private data class CandidateProfile(
        val maxColorDistance: Float,
        val maxLuminanceDrop: Int,
        val useSampledLuminanceFloor: Boolean = false,
    )

    private data class EdgeRegionAttempt(
        val region: BooleanArray?,
        val regionPixels: Int,
        val reason: String,
        val canRetryWithExpandedRoi: Boolean,
    )

    private data class FloodRegion(
        val mask: BooleanArray,
        val pixelCount: Int,
        val touchesBoundary: Boolean,
    )

    private data class FilledRegion(
        val mask: BooleanArray,
        val pixelCount: Int,
    )

    private fun estimateBackgroundAroundText(
        argb: IntArray,
        width: Int,
        roi: IntRect,
        members: List<Polygon>,
        scratch: BubbleBackgroundStatistics.Scratch,
    ): BackgroundColor? {
        val capacity = members.sumOf { polygon ->
            val bounds = clamp(polygon.bounds, roi.right, roi.bottom)
            val sampleWidth = (minOf(bounds.right, roi.right) - max(bounds.left, roi.left))
                .coerceAtLeast(0)
            val sampleHeight = (minOf(bounds.bottom, roi.bottom) - max(bounds.top, roi.top))
                .coerceAtLeast(0)
            ((sampleWidth + SAMPLE_STRIDE_PX - 1) / SAMPLE_STRIDE_PX) *
                ((sampleHeight + SAMPLE_STRIDE_PX - 1) / SAMPLE_STRIDE_PX)
        }
        scratch.ensureSampleCapacity(capacity)
        var sampleCount = 0
        members.forEach { polygon ->
            val bounds = clamp(polygon.bounds, roi.right, roi.bottom)
            val left = max(bounds.left, roi.left)
            val top = max(bounds.top, roi.top)
            val right = minOf(bounds.right, roi.right)
            val bottom = minOf(bounds.bottom, roi.bottom)
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    if (pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)) {
                        scratch.samples[sampleCount++] = argb[y * width + x]
                    }
                    x += SAMPLE_STRIDE_PX
                }
                y += SAMPLE_STRIDE_PX
            }
        }
        if (sampleCount < MIN_BACKGROUND_SAMPLES) return null
        val estimate = scratch.estimate(
            count = sampleCount,
            brightSampleStartRatio = BRIGHT_SAMPLE_START_RATIO,
            lowSampleRatio = BACKGROUND_LOW_SAMPLE_RATIO,
        )
        return BackgroundColor(
            red = estimate.red,
            green = estimate.green,
            blue = estimate.blue,
            luminance = estimate.luminance,
            lowLuminance = estimate.lowLuminance,
        )
    }

    private fun minimumCandidateLuminance(
        background: BackgroundColor,
        profile: CandidateProfile,
    ): Int = if (profile.useSampledLuminanceFloor) {
        max(
            MIN_CANDIDATE_LUMINANCE,
            background.lowLuminance - SAMPLED_LUMINANCE_FLOOR_TOLERANCE,
        )
    } else {
        max(
            MIN_CANDIDATE_LUMINANCE,
            background.luminance - profile.maxLuminanceDrop,
        )
    }

    private fun acceptedReason(attempts: Int, profileIndex: Int): String = when {
        attempts > 1 && profileIndex > 0 -> "accepted_gradient_after_roi_expand"
        attempts > 1 -> "accepted_after_roi_expand"
        profileIndex > 0 -> "accepted_gradient"
        else -> "accepted"
    }

    /**
     * Color ranges cannot cover both ends of a strong gray gradient without also admitting
     * anti-aliased outline pixels. This fallback treats very dark pixels and strong local
     * luminance gradients as barriers, then floods the area around the OCR members. It is used
     * only when it produces a larger closed region than color segmentation.
     */
    private fun findEdgeBoundedRegion(
        argb: IntArray,
        imageWidth: Int,
        roi: IntRect,
        members: List<Polygon>,
        background: BackgroundColor,
        luminances: IntArray? = null,
        queue: IntArray? = null,
        seedCandidates: List<MangaSeedSearchOrder>? = null,
    ): EdgeRegionAttempt {
        val localLuminances = luminances ?: extractLuminances(argb, imageWidth, roi)
        require(localLuminances.size == roi.width * roi.height)
        val darkBarrierThreshold = (
            background.lowLuminance - EDGE_DARK_BACKGROUND_GAP
            ).coerceIn(EDGE_MIN_DARK_THRESHOLD, EDGE_MAX_DARK_THRESHOLD)
        val barrier = BooleanArray(localLuminances.size)
        for (y in 0 until roi.height) {
            for (x in 0 until roi.width) {
                val index = y * roi.width + x
                val left = localLuminances[y * roi.width + (x - 1).coerceAtLeast(0)]
                val right = localLuminances[y * roi.width + (x + 1).coerceAtMost(roi.width - 1)]
                val top = localLuminances[(y - 1).coerceAtLeast(0) * roi.width + x]
                val bottom = localLuminances[(y + 1).coerceAtMost(roi.height - 1) * roi.width + x]
                val gradient = kotlin.math.abs(right - left) + kotlin.math.abs(bottom - top)
                barrier[index] = localLuminances[index] <= darkBarrierThreshold ||
                    gradient >= EDGE_GRADIENT_THRESHOLD
            }
        }
        val sealedBarrier = BinarySquareDilation.dilate(
            input = barrier,
            width = roi.width,
            height = roi.height,
            radius = EDGE_BARRIER_DILATION_PX,
        )
        val walkable = BooleanArray(sealedBarrier.size) { index -> !sealedBarrier[index] }
        val reusableSeeds = seedCandidates ?: members.map { polygon ->
            buildSeedCandidates(
                polygon = polygon,
                imageWidth = imageWidth,
                imageHeight = argb.size / imageWidth,
            )
        }
        val seeds = reusableSeeds.mapNotNull { it.nearest(walkable, roi) }.distinct()
        if (seeds.isEmpty()) {
            return EdgeRegionAttempt(null, 0, "edge_no_seed", false)
        }
        val flood = floodCandidateRegion(
            candidate = walkable,
            width = roi.width,
            height = roi.height,
            seeds = seeds,
            queue = queue,
        )
        val region = flood.mask
        val regionPixels = flood.pixelCount
        if (regionPixels < MIN_REGION_PIXELS) {
            return EdgeRegionAttempt(null, regionPixels, "edge_region_too_small", false)
        }
        if (flood.touchesBoundary) {
            return EdgeRegionAttempt(null, regionPixels, "edge_region_leaked", true)
        }
        if (regionPixels >= (roi.width * roi.height * MAX_REGION_ROI_RATIO).roundToInt()) {
            return EdgeRegionAttempt(null, regionPixels, "edge_region_too_large", true)
        }
        return EdgeRegionAttempt(region, regionPixels, "accepted_edge", false)
    }

    private fun extractLuminances(
        argb: IntArray,
        imageWidth: Int,
        roi: IntRect,
    ): IntArray {
        val luminances = IntArray(roi.width * roi.height)
        for (localY in 0 until roi.height) {
            val globalOffset = (roi.top + localY) * imageWidth + roi.left
            val localOffset = localY * roi.width
            for (localX in 0 until roi.width) {
                luminances[localOffset + localX] = luminance(argb[globalOffset + localX])
            }
        }
        return luminances
    }

    private fun extractRoiFeatures(
        argb: IntArray,
        imageWidth: Int,
        roi: IntRect,
        background: BackgroundColor,
    ): RoiFeatures {
        val luminances = IntArray(roi.width * roi.height)
        val colorDistanceSquared = IntArray(luminances.size)
        for (localY in 0 until roi.height) {
            val globalOffset = (roi.top + localY) * imageWidth + roi.left
            val localOffset = localY * roi.width
            for (localX in 0 until roi.width) {
                val localIndex = localOffset + localX
                val color = argb[globalOffset + localX]
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val dr = red - background.red
                val dg = green - background.green
                val db = blue - background.blue
                luminances[localIndex] = luminance(red, green, blue)
                colorDistanceSquared[localIndex] = dr * dr + dg * dg + db * db
            }
        }
        return RoiFeatures(
            luminances = luminances,
            colorDistanceSquared = colorDistanceSquared,
        )
    }

    private fun buildSeedCandidates(
        polygon: Polygon,
        imageWidth: Int,
        imageHeight: Int,
    ): MangaSeedSearchOrder {
        val bounds = clamp(polygon.bounds, imageWidth, imageHeight)
        val centerX = polygon.points.map { it.x }.average().toFloat()
        val centerY = polygon.points.map { it.y }.average().toFloat()
        val capacity = bounds.width * bounds.height
        val globalIndices = IntArray(capacity)
        val distances = FloatArray(capacity)
        var count = 0
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                if (!pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)) continue
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                globalIndices[count] = y * imageWidth + x
                distances[count] = dx * dx + dy * dy
                count++
            }
        }
        return MangaSeedSearchOrder.prepare(
            imageWidth = imageWidth,
            globalIndices = globalIndices,
            distanceSquared = distances,
            count = count,
        )
    }

    private fun memberRegionCoverage(
        region: BooleanArray,
        roi: IntRect,
        members: List<Polygon>,
    ): Float {
        var memberPixels = 0
        var coveredPixels = 0
        members.forEach { polygon ->
            val bounds = polygon.bounds
            val left = max(bounds.left, roi.left)
            val top = max(bounds.top, roi.top)
            val right = minOf(bounds.right, roi.right)
            val bottom = minOf(bounds.bottom, roi.bottom)
            for (y in top until bottom) {
                for (x in left until right) {
                    if (!pointInPolygon(x + 0.5f, y + 0.5f, polygon.points)) continue
                    memberPixels++
                    if (region[(y - roi.top) * roi.width + (x - roi.left)]) {
                        coveredPixels++
                    }
                }
            }
        }
        return if (memberPixels == 0) 0f else coveredPixels.toFloat() / memberPixels
    }

    private fun floodCandidateRegion(
        candidate: BooleanArray,
        width: Int,
        height: Int,
        seeds: List<Int>,
        queue: IntArray? = null,
    ): FloodRegion {
        val region = BooleanArray(candidate.size)
        val workQueue = queue ?: IntArray(candidate.size)
        require(workQueue.size >= candidate.size)
        var head = 0
        var tail = 0
        var reachedBoundary = false
        seeds.forEach { seed ->
            if (seed in candidate.indices && candidate[seed] && !region[seed]) {
                region[seed] = true
                workQueue[tail++] = seed
            }
        }
        while (head < tail) {
            val index = workQueue[head++]
            val x = index % width
            val y = index / width
            if (x == 0 || x + 1 == width || y == 0 || y + 1 == height) {
                reachedBoundary = true
            }
            if (x > 0) tail = enqueue(index - 1, candidate, region, workQueue, tail)
            if (x + 1 < width) tail = enqueue(index + 1, candidate, region, workQueue, tail)
            if (y > 0) tail = enqueue(index - width, candidate, region, workQueue, tail)
            if (y + 1 < height) tail = enqueue(index + width, candidate, region, workQueue, tail)
        }
        return FloodRegion(
            mask = region,
            pixelCount = tail,
            touchesBoundary = reachedBoundary,
        )
    }

    private fun enqueue(
        index: Int,
        candidate: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (!candidate[index] || visited[index]) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun fillEnclosedHoles(
        region: BooleanArray,
        width: Int,
        height: Int,
        queue: IntArray? = null,
    ): FilledRegion {
        val exterior = BooleanArray(region.size)
        val workQueue = queue ?: IntArray(region.size)
        require(workQueue.size >= region.size)
        var head = 0
        var tail = 0

        fun seed(index: Int) {
            if (!region[index] && !exterior[index]) {
                exterior[index] = true
                workQueue[tail++] = index
            }
        }
        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }
        while (head < tail) {
            val index = workQueue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) tail = enqueueComplement(index - 1, region, exterior, workQueue, tail)
            if (x + 1 < width) {
                tail = enqueueComplement(index + 1, region, exterior, workQueue, tail)
            }
            if (y > 0) tail = enqueueComplement(index - width, region, exterior, workQueue, tail)
            if (y + 1 < height) {
                tail = enqueueComplement(index + width, region, exterior, workQueue, tail)
            }
        }
        var pixelCount = 0
        val filled = BooleanArray(region.size) { index ->
            (region[index] || !exterior[index]).also { included ->
                if (included) pixelCount++
            }
        }
        return FilledRegion(filled, pixelCount)
    }

    private fun enqueueComplement(
        index: Int,
        region: BooleanArray,
        exterior: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (region[index] || exterior[index]) return tail
        exterior[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun touchesBoundary(mask: BooleanArray, width: Int, height: Int): Boolean {
        for (x in 0 until width) {
            if (mask[x] || mask[(height - 1) * width + x]) return true
        }
        for (y in 0 until height) {
            if (mask[y * width] || mask[y * width + width - 1]) return true
        }
        return false
    }

    private fun rejected(
        roi: IntRect,
        reason: String,
        regionPixels: Int = 0,
        attempts: Int = 1,
    ): BubbleDiagnostic = BubbleDiagnostic(
        roi = roi,
        accepted = false,
        confidence = 0f,
        reason = reason,
        regionPixels = regionPixels,
        attempts = attempts,
    )

    private fun pointInPolygon(x: Float, y: Float, points: List<Point>): Boolean {
        var inside = false
        var previous = points.last()
        for (current in points) {
            if (
                (current.y > y) != (previous.y > y) &&
                x < (previous.x - current.x) * (y - current.y) /
                    (previous.y - current.y + 1e-6f) + current.x
            ) {
                inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun clamp(rect: IntRect, width: Int, height: Int): IntRect = IntRect(
        left = rect.left.coerceIn(0, width),
        top = rect.top.coerceIn(0, height),
        right = rect.right.coerceIn(0, width),
        bottom = rect.bottom.coerceIn(0, height),
    )

    private fun luminance(color: Int): Int = luminance(
        red = (color ushr 16) and 0xFF,
        green = (color ushr 8) and 0xFF,
        blue = color and 0xFF,
    )

    private fun luminance(red: Int, green: Int, blue: Int): Int =
        (red * 299 + green * 587 + blue * 114) / 1000

    private const val TEXT_MASK_DILATION_PX = 1
    private const val ROI_MARGIN_RATIO = 0.35f
    private const val MIN_ROI_MARGIN_PX = 32
    private const val MAX_ROI_MARGIN_PX = 160
    private const val ROI_RETRY_MARGIN_MULTIPLIER = 2f
    private const val MAX_RETRY_ROI_MARGIN_PX = 320
    private const val MIN_ROI_SIDE_PX = 12
    private const val MIN_CONTENT_LONG_SIDE_PX = 24
    private const val MIN_CONTENT_LONG_SIDE_SCREEN_DIVISOR = 40
    private const val MIN_CONTENT_AREA_PX = 256L
    private const val MIN_CONTENT_AREA_SCREEN_DIVISOR = 4_000L
    private const val MIN_TEXT_LUMINANCE_SAMPLES = 16
    private const val MIN_TEXT_LUMINANCE_CONTRAST = 24
    private const val SURROUNDING_SAMPLE_MARGIN_RATIO = 0.25f
    private const val MIN_SURROUNDING_SAMPLE_MARGIN_PX = 2
    private const val MAX_SURROUNDING_SAMPLE_MARGIN_PX = 24
    private const val MIN_SURROUNDING_LUMINANCE_SAMPLES = 16
    private const val SAMPLE_STRIDE_PX = 3
    private const val MIN_BACKGROUND_SAMPLES = 12
    private const val BRIGHT_SAMPLE_START_RATIO = 0.45f
    private const val BACKGROUND_LOW_SAMPLE_RATIO = 0.30f
    private const val MIN_BACKGROUND_LUMINANCE = 72
    private const val MIN_CANDIDATE_LUMINANCE = 40
    private const val SAMPLED_LUMINANCE_FLOOR_TOLERANCE = 24
    private val CANDIDATE_PROFILES = listOf(
        CandidateProfile(
            maxColorDistance = 165f,
            maxLuminanceDrop = 110,
        ),
        CandidateProfile(
            maxColorDistance = 225f,
            maxLuminanceDrop = 0,
            useSampledLuminanceFloor = true,
        ),
    )
    private const val MIN_REGION_PIXELS = 24
    private const val MIN_MEMBER_REGION_COVERAGE = 0.72f
    private const val EDGE_DARK_BACKGROUND_GAP = 32
    private const val EDGE_MIN_DARK_THRESHOLD = 24
    private const val EDGE_MAX_DARK_THRESHOLD = 64
    private const val EDGE_GRADIENT_THRESHOLD = 72
    private const val EDGE_BARRIER_DILATION_PX = 1
    private const val EDGE_PREFERRED_SIZE_RATIO = 1.05f
    private const val IDEAL_REGION_ROI_RATIO = 0.35f
    private const val MAX_REGION_ROI_RATIO = 0.82f
}

/**
 * Merges the DBNet probability maps from the full image and optional overlapping tiles into
 * source-image coordinates. Allocation only happens while manga mask diagnostics are enabled.
 */
internal class MangaProbabilityMaskAccumulator(
    val width: Int,
    val height: Int,
) {
    private val pixels = BooleanArray(width * height)

    fun merge(
        probabilityMap: Array<FloatArray>,
        scaleX: Float,
        scaleY: Float,
        offsetX: Int,
        offsetY: Int,
        threshold: Float,
    ) {
        probabilityMap.forEachIndexed { mapY, row ->
            row.forEachIndexed { mapX, probability ->
                if (probability < threshold) return@forEachIndexed
                val left = (floor(mapX * scaleX).toInt() + offsetX).coerceIn(0, width)
                val top = (floor(mapY * scaleY).toInt() + offsetY).coerceIn(0, height)
                val right = (ceil((mapX + 1) * scaleX).toInt() + offsetX).coerceIn(0, width)
                val bottom = (ceil((mapY + 1) * scaleY).toInt() + offsetY).coerceIn(0, height)
                for (y in top until bottom) {
                    val rowOffset = y * width
                    for (x in left until right) pixels[rowOffset + x] = true
                }
            }
        }
    }

    fun snapshot(): BooleanArray = pixels.copyOf()
}
