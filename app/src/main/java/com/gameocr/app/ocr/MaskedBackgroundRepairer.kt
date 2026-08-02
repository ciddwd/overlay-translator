package com.gameocr.app.ocr

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Produces a background-repair preview for an already validated erase mask.
 *
 * The repair is local and dependency-free: it estimates the dominant clean color around each
 * connected erase component, then fills flat regions or interpolates from nearby clean pixels.
 * Callers remain conservative by default, while an explicit capability can prefer complete text
 * removal on complex backgrounds.
 */
internal object MaskedBackgroundRepairer {

    enum class Reason {
        REPAIRED,
        INSUFFICIENT_BOUNDARY_SAMPLES,
        BACKGROUND_TOO_COMPLEX,
        BACKGROUND_NOT_FLAT,
    }

    enum class Mode {
        NONE,
        DOMINANT_FILL,
        DIRECTIONAL_INTERPOLATION,
    }

    data class ComponentDecision(
        val componentIndex: Int,
        val accepted: Boolean,
        val reason: Reason,
        val mode: Mode,
        val erasePixels: Int,
        val boundarySamples: Int,
        val dominantInlierFraction: Float,
        val colorSpread: Float,
    )

    data class Result(
        val pixels: IntArray,
        val repairedMask: BooleanArray,
        val decisions: List<ComponentDecision>,
    ) {
        val acceptedComponentCount: Int
            get() = decisions.count { it.accepted }

        val rejectedComponentCount: Int
            get() = decisions.count { !it.accepted }

        val repairedPixelCount: Int
            get() = repairedMask.count { it }

        val dominantFillComponentCount: Int
            get() = decisions.count { it.mode == Mode.DOMINANT_FILL }
    }

    fun repair(
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        eraseMask: BooleanArray,
        allowedSampleMask: BooleanArray,
        flatCompletionMask: BooleanArray = eraseMask,
        allowDirectionalInterpolation: Boolean = false,
        allowComplexBackgroundInterpolation: Boolean = false,
        foregroundReferenceMask: BooleanArray? = null,
    ): Result {
        require(width > 0 && height > 0)
        require(sourceArgb.size == width * height)
        require(eraseMask.size == sourceArgb.size)
        require(allowedSampleMask.size == sourceArgb.size)
        require(flatCompletionMask.size == sourceArgb.size)
        require(foregroundReferenceMask == null || foregroundReferenceMask.size == sourceArgb.size)

        val output = sourceArgb.copyOf()
        val repairedMask = BooleanArray(eraseMask.size)
        val eraseComponents = labelComponents(width, height, eraseMask)
        val completionComponents = labelComponents(width, height, flatCompletionMask)
        val sampleMarks = IntArray(eraseMask.size)
        var sampleStamp = 0
        val plans = eraseComponents.items.mapIndexed { componentIndex, component ->
            sampleStamp++
            val samples = collectBoundarySamples(
                width = width,
                height = height,
                eraseMask = eraseMask,
                allowedSampleMask = allowedSampleMask,
                component = component,
                sampleMarks = sampleMarks,
                sampleStamp = sampleStamp,
            )
            if (samples.size < minimumBoundarySamples(component.size)) {
                return@mapIndexed RepairPlan(
                    component = component,
                    estimate = null,
                    decision = rejected(
                        componentIndex = componentIndex,
                        reason = Reason.INSUFFICIENT_BOUNDARY_SAMPLES,
                        erasePixels = component.size,
                        boundarySamples = samples.size,
                    ),
                )
            }

            val dominantEstimate = estimateDominantBackground(sourceArgb, samples)
            val backgroundTooComplex =
                dominantEstimate.inlierFraction < MIN_DOMINANT_INLIER_FRACTION ||
                dominantEstimate.colorSpread > MAX_DOMINANT_COLOR_SPREAD
            if (backgroundTooComplex && !allowComplexBackgroundInterpolation) {
                return@mapIndexed RepairPlan(
                    component = component,
                    estimate = dominantEstimate,
                    decision = rejected(
                        componentIndex = componentIndex,
                        reason = Reason.BACKGROUND_TOO_COMPLEX,
                        erasePixels = component.size,
                        boundarySamples = samples.size,
                        dominantInlierFraction = dominantEstimate.inlierFraction,
                        colorSpread = dominantEstimate.colorSpread,
                    ),
                )
            }
            val estimate = if (backgroundTooComplex && allowComplexBackgroundInterpolation) {
                foregroundReferenceMask
                    ?.let { referenceMask ->
                        estimateContrastingBackground(
                            sourceArgb = sourceArgb,
                            backgroundSamples = samples,
                            foregroundSamples = component.filter { referenceMask[it] }.toIntArray(),
                        )
                    }
                    ?: dominantEstimate
            } else {
                dominantEstimate
            }
            if (
                estimate.colorSpread > MAX_FLAT_BACKGROUND_SPREAD &&
                !allowDirectionalInterpolation
            ) {
                return@mapIndexed RepairPlan(
                    component = component,
                    estimate = estimate,
                    decision = rejected(
                        componentIndex = componentIndex,
                        reason = Reason.BACKGROUND_NOT_FLAT,
                        erasePixels = component.size,
                        boundarySamples = samples.size,
                        dominantInlierFraction = estimate.inlierFraction,
                        colorSpread = estimate.colorSpread,
                    ),
                )
            }
            val mode = if (
                backgroundTooComplex ||
                estimate.colorSpread > MAX_FLAT_BACKGROUND_SPREAD
            ) {
                Mode.DIRECTIONAL_INTERPOLATION
            } else {
                Mode.DOMINANT_FILL
            }
            RepairPlan(
                component = component,
                estimate = estimate,
                decision = ComponentDecision(
                    componentIndex = componentIndex,
                    accepted = true,
                    reason = Reason.REPAIRED,
                    mode = mode,
                    erasePixels = component.size,
                    boundarySamples = samples.size,
                    dominantInlierFraction = estimate.inlierFraction,
                    colorSpread = estimate.colorSpread,
                ),
            )
        }

        val completionOwners = Array(completionComponents.items.size) { linkedSetOf<Int>() }
        plans.forEachIndexed { planIndex, plan ->
            plan.component.forEach { index ->
                val completionLabel = completionComponents.labels[index]
                if (completionLabel >= 0) completionOwners[completionLabel] += planIndex
            }
        }
        val completionFillOwner = IntArray(completionComponents.items.size) { NO_COMPONENT }
        completionOwners.forEachIndexed { completionLabel, owners ->
            if (
                owners.isNotEmpty() &&
                owners.all { planIndex ->
                    plans[planIndex].decision.mode == Mode.DOMINANT_FILL
                }
            ) {
                completionFillOwner[completionLabel] = owners.min()
            }
        }

        plans.forEachIndexed { planIndex, plan ->
            val estimate = plan.estimate ?: return@forEachIndexed
            when (plan.decision.mode) {
                Mode.DOMINANT_FILL -> {
                    plan.component.forEach { index ->
                        val completionLabel = completionComponents.labels[index]
                        if (
                            completionLabel == NO_COMPONENT ||
                            completionFillOwner[completionLabel] == NO_COMPONENT
                        ) {
                            fillPixel(
                                index = index,
                                color = estimate.color,
                                output = output,
                                repairedMask = repairedMask,
                            )
                        }
                    }
                    completionFillOwner.forEachIndexed { completionLabel, owner ->
                        if (owner != planIndex) return@forEachIndexed
                        completionComponents.items[completionLabel].forEach { index ->
                            if (allowedSampleMask[index]) {
                                fillPixel(
                                    index = index,
                                    color = estimate.color,
                                    output = output,
                                    repairedMask = repairedMask,
                                )
                            }
                        }
                    }
                }
                Mode.DIRECTIONAL_INTERPOLATION -> {
                    plan.component.forEach { index ->
                        fillPixel(
                            index = index,
                            color = interpolatePixel(
                                index = index,
                                width = width,
                                height = height,
                                sourceArgb = sourceArgb,
                                eraseMask = eraseMask,
                                allowedSampleMask = allowedSampleMask,
                                referenceColor = estimate.color,
                            ),
                            output = output,
                            repairedMask = repairedMask,
                        )
                    }
                }
                Mode.NONE -> Unit
            }
        }
        return Result(
            pixels = output,
            repairedMask = repairedMask,
            decisions = plans.map { it.decision },
        )
    }

    private data class RepairPlan(
        val component: IntArray,
        val estimate: BackgroundEstimate?,
        val decision: ComponentDecision,
    )

    private data class Components(
        val labels: IntArray,
        val items: List<IntArray>,
    )

    private fun labelComponents(
        width: Int,
        height: Int,
        mask: BooleanArray,
    ): Components {
        val visited = BooleanArray(mask.size)
        val labels = IntArray(mask.size) { NO_COMPONENT }
        val queue = IntArray(mask.size)
        val components = mutableListOf<IntArray>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            labels[start] = components.size
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    val nextY = y + dy
                    if (nextY !in 0 until height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = x + dx
                        if (nextX !in 0 until width) continue
                        val next = nextY * width + nextX
                        if (mask[next] && !visited[next]) {
                            visited[next] = true
                            labels[next] = components.size
                            queue[tail++] = next
                        }
                    }
                }
            }
            components += queue.copyOf(tail)
        }
        return Components(labels = labels, items = components)
    }

    private fun fillPixel(
        index: Int,
        color: Int,
        output: IntArray,
        repairedMask: BooleanArray,
    ) {
        output[index] = color
        repairedMask[index] = true
    }

    private fun collectBoundarySamples(
        width: Int,
        height: Int,
        eraseMask: BooleanArray,
        allowedSampleMask: BooleanArray,
        component: IntArray,
        sampleMarks: IntArray,
        sampleStamp: Int,
    ): IntArray {
        val samples = IntArray(MAX_BOUNDARY_SAMPLES)
        var count = 0
        component.forEach { index ->
            if (count >= samples.size) return@forEach
            val x = index % width
            val y = index / width
            if (!isComponentBoundary(x, y, width, height, eraseMask)) return@forEach
            for (dy in -BOUNDARY_OUTER_RADIUS..BOUNDARY_OUTER_RADIUS) {
                val sampleY = y + dy
                if (sampleY !in 0 until height) continue
                for (dx in -BOUNDARY_OUTER_RADIUS..BOUNDARY_OUTER_RADIUS) {
                    val distanceSquared = dx * dx + dy * dy
                    if (
                        distanceSquared < BOUNDARY_INNER_RADIUS * BOUNDARY_INNER_RADIUS ||
                        distanceSquared > BOUNDARY_OUTER_RADIUS * BOUNDARY_OUTER_RADIUS
                    ) {
                        continue
                    }
                    val sampleX = x + dx
                    if (sampleX !in 0 until width) continue
                    val sample = sampleY * width + sampleX
                    if (
                        eraseMask[sample] ||
                        !allowedSampleMask[sample] ||
                        sampleMarks[sample] == sampleStamp
                    ) {
                        continue
                    }
                    sampleMarks[sample] = sampleStamp
                    samples[count++] = sample
                    if (count >= samples.size) break
                }
                if (count >= samples.size) break
            }
        }
        return samples.copyOf(count)
    }

    private fun isComponentBoundary(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: BooleanArray,
    ): Boolean {
        if (x == 0 || y == 0 || x + 1 == width || y + 1 == height) return true
        val index = y * width + x
        return !mask[index - 1] ||
            !mask[index + 1] ||
            !mask[index - width] ||
            !mask[index + width]
    }

    private data class BackgroundEstimate(
        val color: Int,
        val inlierFraction: Float,
        val colorSpread: Float,
    )

    private fun estimateDominantBackground(
        sourceArgb: IntArray,
        samples: IntArray,
    ): BackgroundEstimate {
        val histogram = IntArray(COLOR_BIN_COUNT)
        samples.forEach { index ->
            histogram[colorBin(sourceArgb[index])]++
        }
        var dominantBin = 0
        for (index in 1 until histogram.size) {
            if (histogram[index] > histogram[dominantBin]) dominantBin = index
        }
        val binColor = binCenterColor(dominantBin)
        val firstPass = samples.filter { index ->
            colorDistanceSquared(sourceArgb[index], binColor) <= DOMINANT_COLOR_RADIUS_SQUARED
        }
        val representative = if (firstPass.isEmpty()) {
            binColor
        } else {
            medianColor(sourceArgb, firstPass)
        }
        val inliers = samples.filter { index ->
            colorDistanceSquared(sourceArgb[index], representative) <=
                DOMINANT_COLOR_RADIUS_SQUARED
        }
        val spread = if (inliers.isEmpty()) {
            Float.POSITIVE_INFINITY
        } else {
            inliers.sumOf { index ->
                sqrt(colorDistanceSquared(sourceArgb[index], representative).toDouble())
            }.toFloat() / inliers.size
        }
        return BackgroundEstimate(
            color = representative,
            inlierFraction = inliers.size.toFloat() / samples.size,
            colorSpread = spread,
        )
    }

    private fun estimateContrastingBackground(
        sourceArgb: IntArray,
        backgroundSamples: IntArray,
        foregroundSamples: IntArray,
    ): BackgroundEstimate? {
        if (backgroundSamples.isEmpty() || foregroundSamples.isEmpty()) return null
        val foreground = estimateDominantBackground(sourceArgb, foregroundSamples).color
        val histogram = IntArray(COLOR_BIN_COUNT)
        backgroundSamples.forEach { index ->
            histogram[colorBin(sourceArgb[index])]++
        }
        var selectedBin = -1
        var selectedScore = Long.MIN_VALUE
        var selectedSupport = -1
        histogram.forEachIndexed { bin, support ->
            if (support == 0) return@forEachIndexed
            val contrast = colorDistanceSquared(binCenterColor(bin), foreground)
            val score = support.toLong() * contrast
            if (
                score > selectedScore ||
                (score == selectedScore && support > selectedSupport)
            ) {
                selectedBin = bin
                selectedScore = score
                selectedSupport = support
            }
        }
        if (selectedBin < 0) return null
        val selectedCenter = binCenterColor(selectedBin)
        val selectedCluster = backgroundSamples.filter { index ->
            colorDistanceSquared(sourceArgb[index], selectedCenter) <=
                DOMINANT_COLOR_RADIUS_SQUARED
        }
        if (selectedCluster.isEmpty()) return null
        val representative = medianColor(sourceArgb, selectedCluster)
        val inliers = backgroundSamples.filter { index ->
            colorDistanceSquared(sourceArgb[index], representative) <=
                DOMINANT_COLOR_RADIUS_SQUARED
        }
        val spread = inliers.sumOf { index ->
            sqrt(colorDistanceSquared(sourceArgb[index], representative).toDouble())
        }.toFloat() / inliers.size
        return BackgroundEstimate(
            color = representative,
            inlierFraction = inliers.size.toFloat() / backgroundSamples.size,
            colorSpread = spread,
        )
    }

    private fun medianColor(
        sourceArgb: IntArray,
        sampleIndices: List<Int>,
    ): Int {
        val alpha = IntArray(sampleIndices.size)
        val red = IntArray(sampleIndices.size)
        val green = IntArray(sampleIndices.size)
        val blue = IntArray(sampleIndices.size)
        sampleIndices.forEachIndexed { position, index ->
            val color = sourceArgb[index]
            alpha[position] = color ushr 24 and 0xff
            red[position] = color ushr 16 and 0xff
            green[position] = color ushr 8 and 0xff
            blue[position] = color and 0xff
        }
        alpha.sort()
        red.sort()
        green.sort()
        blue.sort()
        val middle = sampleIndices.size / 2
        return argb(alpha[middle], red[middle], green[middle], blue[middle])
    }

    private fun interpolatePixel(
        index: Int,
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        eraseMask: BooleanArray,
        allowedSampleMask: BooleanArray,
        referenceColor: Int,
    ): Int {
        val x = index % width
        val y = index / width
        var alpha = 0.0
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weightSum = 0.0
        DIRECTIONS.forEach { direction ->
            val sample = findDirectionalSample(
                x = x,
                y = y,
                dx = direction.first,
                dy = direction.second,
                width = width,
                height = height,
                sourceArgb = sourceArgb,
                eraseMask = eraseMask,
                allowedSampleMask = allowedSampleMask,
                referenceColor = referenceColor,
            ) ?: return@forEach
            val color = sourceArgb[sample.index]
            val weight = 1.0 / (sample.distance * sample.distance)
            alpha += (color ushr 24 and 0xff) * weight
            red += (color ushr 16 and 0xff) * weight
            green += (color ushr 8 and 0xff) * weight
            blue += (color and 0xff) * weight
            weightSum += weight
        }
        if (weightSum <= 0.0) return referenceColor
        return argb(
            alpha = (alpha / weightSum).roundToInt().coerceIn(0, 255),
            red = (red / weightSum).roundToInt().coerceIn(0, 255),
            green = (green / weightSum).roundToInt().coerceIn(0, 255),
            blue = (blue / weightSum).roundToInt().coerceIn(0, 255),
        )
    }

    private data class DirectionalSample(
        val index: Int,
        val distance: Int,
    )

    private fun findDirectionalSample(
        x: Int,
        y: Int,
        dx: Int,
        dy: Int,
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        eraseMask: BooleanArray,
        allowedSampleMask: BooleanArray,
        referenceColor: Int,
    ): DirectionalSample? {
        for (distance in 1..MAX_DIRECTIONAL_SEARCH) {
            val sampleX = x + dx * distance
            val sampleY = y + dy * distance
            if (sampleX !in 0 until width || sampleY !in 0 until height) return null
            val sample = sampleY * width + sampleX
            if (eraseMask[sample] || !allowedSampleMask[sample]) continue
            if (
                colorDistanceSquared(sourceArgb[sample], referenceColor) <=
                DIRECTIONAL_COLOR_RADIUS_SQUARED
            ) {
                return DirectionalSample(sample, distance)
            }
        }
        return null
    }

    private fun colorBin(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return (red ushr COLOR_BIN_SHIFT shl 8) or
            (green ushr COLOR_BIN_SHIFT shl 4) or
            (blue ushr COLOR_BIN_SHIFT)
    }

    private fun binCenterColor(bin: Int): Int {
        val red = ((bin ushr 8) and 0xf shl COLOR_BIN_SHIFT) + COLOR_BIN_HALF
        val green = ((bin ushr 4) and 0xf shl COLOR_BIN_SHIFT) + COLOR_BIN_HALF
        val blue = (bin and 0xf shl COLOR_BIN_SHIFT) + COLOR_BIN_HALF
        return argb(255, red, green, blue)
    }

    private fun colorDistanceSquared(first: Int, second: Int): Int {
        val red = (first ushr 16 and 0xff) - (second ushr 16 and 0xff)
        val green = (first ushr 8 and 0xff) - (second ushr 8 and 0xff)
        val blue = (first and 0xff) - (second and 0xff)
        return red * red + green * green + blue * blue
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun minimumBoundarySamples(componentPixels: Int): Int =
        maxOf(
            MIN_BOUNDARY_SAMPLES,
            (sqrt(componentPixels.toDouble()) * MIN_SAMPLE_EDGE_RATIO).roundToInt(),
        ).coerceAtMost(MAX_REQUIRED_BOUNDARY_SAMPLES)

    private fun rejected(
        componentIndex: Int,
        reason: Reason,
        erasePixels: Int,
        boundarySamples: Int,
        dominantInlierFraction: Float = 0f,
        colorSpread: Float = 0f,
    ): ComponentDecision = ComponentDecision(
        componentIndex = componentIndex,
        accepted = false,
        reason = reason,
        mode = Mode.NONE,
        erasePixels = erasePixels,
        boundarySamples = boundarySamples,
        dominantInlierFraction = dominantInlierFraction,
        colorSpread = colorSpread,
    )

    private const val BOUNDARY_INNER_RADIUS = 2
    private const val BOUNDARY_OUTER_RADIUS = 7
    private const val NO_COMPONENT = -1
    private const val MAX_BOUNDARY_SAMPLES = 8_192
    private const val MIN_BOUNDARY_SAMPLES = 16
    private const val MAX_REQUIRED_BOUNDARY_SAMPLES = 96
    private const val MIN_SAMPLE_EDGE_RATIO = 1.5

    private const val COLOR_BIN_SHIFT = 4
    private const val COLOR_BIN_HALF = 1 shl (COLOR_BIN_SHIFT - 1)
    private const val COLOR_BIN_COUNT = 16 * 16 * 16
    private const val DOMINANT_COLOR_RADIUS = 48
    private const val DOMINANT_COLOR_RADIUS_SQUARED =
        DOMINANT_COLOR_RADIUS * DOMINANT_COLOR_RADIUS
    private const val MIN_DOMINANT_INLIER_FRACTION = 0.62f
    private const val MAX_DOMINANT_COLOR_SPREAD = 26f
    private const val MAX_FLAT_BACKGROUND_SPREAD = 9f

    private const val MAX_DIRECTIONAL_SEARCH = 28
    private const val DIRECTIONAL_COLOR_RADIUS = 64
    private const val DIRECTIONAL_COLOR_RADIUS_SQUARED =
        DIRECTIONAL_COLOR_RADIUS * DIRECTIONAL_COLOR_RADIUS

    private val DIRECTIONS = arrayOf(
        -1 to 0,
        1 to 0,
        0 to -1,
        0 to 1,
        -1 to -1,
        1 to -1,
        -1 to 1,
        1 to 1,
    )
}
