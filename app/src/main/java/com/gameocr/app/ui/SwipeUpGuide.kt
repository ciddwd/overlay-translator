package com.gameocr.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.min

internal data class SwipeUpGuideFrame(
    val handOffsetFraction: Float,
    val handAlpha: Float,
    val touchRingAlpha: Float,
    val touchRingScale: Float,
    val trailAlpha: Float,
)

internal data class SwipeHorizontalGuideFrame(
    val handOffsetFraction: Float,
    val handAlpha: Float,
    val touchRingAlpha: Float,
    val touchRingScale: Float,
    val trailAlpha: Float,
)

internal fun swipeUpGuideFrame(progress: Float): SwipeUpGuideFrame {
    val boundedProgress = when {
        progress.isNaN() || progress <= 0f -> 0f
        progress >= 1f -> 1f
        else -> progress
    }
    val fadeIn = phaseFraction(boundedProgress, start = 0f, end = 0.16f)
    val fadeOut = 1f - phaseFraction(boundedProgress, start = 0.72f, end = 0.86f)
    val touchRingAlpha = when {
        boundedProgress < 0.10f -> 0f
        boundedProgress < 0.18f -> phaseFraction(boundedProgress, 0.10f, 0.18f)
        boundedProgress < 0.34f -> 1f - phaseFraction(boundedProgress, 0.18f, 0.34f)
        else -> 0f
    }
    val trailFadeIn = phaseFraction(boundedProgress, start = 0.18f, end = 0.30f)
    val trailFadeOut = 1f - phaseFraction(boundedProgress, start = 0.68f, end = 0.86f)

    return SwipeUpGuideFrame(
        handOffsetFraction = phaseFraction(boundedProgress, start = 0.24f, end = 0.72f),
        handAlpha = (fadeIn * fadeOut).coerceIn(0f, 1f),
        touchRingAlpha = touchRingAlpha,
        touchRingScale = 0.70f + phaseFraction(boundedProgress, 0.10f, 0.34f) * 0.80f,
        trailAlpha = min(trailFadeIn, trailFadeOut).coerceIn(0f, 1f),
    )
}

internal fun swipeHorizontalGuideFrame(progress: Float): SwipeHorizontalGuideFrame {
    val boundedProgress = when {
        progress.isNaN() || progress <= 0f -> 0f
        progress >= 1f -> 1f
        else -> progress
    }
    val fadeIn = phaseFraction(boundedProgress, start = 0f, end = 0.14f)
    val fadeOut = 1f - phaseFraction(boundedProgress, start = 0.82f, end = 0.94f)
    val offset = when {
        boundedProgress < 0.20f -> 0f
        boundedProgress < 0.48f -> phaseFraction(boundedProgress, 0.20f, 0.48f)
        boundedProgress < 0.80f ->
            1f - 2f * phaseFraction(boundedProgress, 0.48f, 0.80f)
        else -> -1f
    }
    val touchRingAlpha = when {
        boundedProgress < 0.08f -> 0f
        boundedProgress < 0.16f -> phaseFraction(boundedProgress, 0.08f, 0.16f)
        boundedProgress < 0.30f -> 1f - phaseFraction(boundedProgress, 0.16f, 0.30f)
        else -> 0f
    }
    val trailFadeIn = phaseFraction(boundedProgress, start = 0.16f, end = 0.28f)
    val trailFadeOut = 1f - phaseFraction(boundedProgress, start = 0.78f, end = 0.94f)

    return SwipeHorizontalGuideFrame(
        handOffsetFraction = offset.coerceIn(-1f, 1f),
        handAlpha = (fadeIn * fadeOut).coerceIn(0f, 1f),
        touchRingAlpha = touchRingAlpha,
        touchRingScale = 0.70f + phaseFraction(boundedProgress, 0.08f, 0.30f) * 0.80f,
        trailAlpha = min(trailFadeIn, trailFadeOut).coerceIn(0f, 1f),
    )
}

/** Keeps all discovery arrows, trails, and touch rings on the app's established guide blue. */
@Suppress("UNUSED_PARAMETER")
internal fun swipeUpGuideAccent(themePrimary: Color): Color = GUIDE_ACCENT_BLUE

internal data class SwipeUpGuideGeometry(
    val restingTop: Float,
    val travel: Float,
    val arrowTop: Float,
    val arrowBottom: Float,
)

internal fun swipeUpGuideGeometry(scale: Float): SwipeUpGuideGeometry =
    SwipeUpGuideGeometry(
        restingTop = 60f * scale,
        travel = 60f * scale,
        arrowTop = 0f,
        arrowBottom = 92f * scale,
    )

internal data class SwipeHorizontalGuideGeometry(
    val centerHandLeft: Float,
    val handTravel: Float,
    val arrowLeft: Float,
    val arrowRight: Float,
)

internal fun swipeHorizontalGuideGeometry(
    canvasWidth: Float,
    scale: Float,
): SwipeHorizontalGuideGeometry = SwipeHorizontalGuideGeometry(
    centerHandLeft = (canvasWidth - HAND_WIDTH * scale) / 2f,
    handTravel = HORIZONTAL_HAND_TRAVEL * scale,
    arrowLeft = 7f * scale,
    arrowRight = canvasWidth - 7f * scale,
)

private fun phaseFraction(progress: Float, start: Float, end: Float): Float {
    if (end <= start) return if (progress >= end) 1f else 0f
    return ((progress - start) / (end - start)).coerceIn(0f, 1f)
}

@Composable
internal fun SwipeUpGuide(modifier: Modifier = Modifier) {
    val guideAccent = swipeUpGuideAccent(MaterialTheme.colorScheme.primary)
    val transition = rememberInfiniteTransition(label = "main-status-swipe-guide")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = SWIPE_UP_GUIDE_DURATION_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "swipe-progress",
    )
    val frame = swipeUpGuideFrame(progress)

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val scale = min(
            size.width / GUIDE_VIEWBOX_WIDTH,
            size.height / VERTICAL_GUIDE_VIEWBOX_HEIGHT,
        )
        val handLeft = (size.width - HAND_WIDTH * scale) / 2f
        val geometry = swipeUpGuideGeometry(scale)
        val handTop = geometry.restingTop - frame.handOffsetFraction * geometry.travel
        val accent = guideAccent.copy(alpha = frame.trailAlpha)
        val touchPoint = Offset(
            x = handLeft + 26f * scale,
            y = handTop + 2f * scale,
        )

        if (frame.trailAlpha > 0f) {
            val arrowX = touchPoint.x
            val arrowTop = geometry.arrowTop
            val arrowBottom = geometry.arrowBottom
            val strokeWidth = 3f * scale
            val dashLength = 5f * scale
            val dashGap = 4f * scale
            var dashBottom = arrowBottom
            while (dashBottom - dashLength > arrowTop + 5f * scale) {
                drawLine(
                    color = accent,
                    start = Offset(arrowX, dashBottom),
                    end = Offset(arrowX, dashBottom - dashLength),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                dashBottom -= dashLength + dashGap
            }
            drawLine(
                color = accent,
                start = Offset(arrowX, arrowTop),
                end = Offset(arrowX - 5f * scale, arrowTop + 6f * scale),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent,
                start = Offset(arrowX, arrowTop),
                end = Offset(arrowX + 5f * scale, arrowTop + 6f * scale),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        if (frame.touchRingAlpha > 0f) {
            drawCircle(
                color = guideAccent.copy(alpha = frame.touchRingAlpha * 0.75f),
                radius = 6f * scale * frame.touchRingScale,
                center = touchPoint,
                style = Stroke(width = 2.5f * scale),
            )
        }

        val hand = handPath(scale)
        withTransform({ translate(left = handLeft, top = handTop) }) {
            drawPath(
                path = hand,
                color = GUIDE_HAND_FILL.copy(alpha = frame.handAlpha),
            )
            drawPath(
                path = hand,
                color = GUIDE_HAND_OUTLINE.copy(alpha = frame.handAlpha),
                style = Stroke(
                    width = 3.5f * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawLine(
                color = GUIDE_HAND_OUTLINE.copy(alpha = frame.handAlpha),
                start = Offset(33f * scale, 24f * scale),
                end = Offset(33f * scale, 34f * scale),
                strokeWidth = 2.5f * scale,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = GUIDE_HAND_OUTLINE.copy(alpha = frame.handAlpha),
                start = Offset(47f * scale, 29f * scale),
                end = Offset(47f * scale, 36f * scale),
                strokeWidth = 2.5f * scale,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun SwipeHorizontalGuide(modifier: Modifier = Modifier) {
    val guideAccent = swipeUpGuideAccent(MaterialTheme.colorScheme.primary)
    val transition = rememberInfiniteTransition(label = "main-horizontal-swipe-guide")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = SWIPE_HORIZONTAL_GUIDE_DURATION_MS,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "horizontal-swipe-progress",
    )
    val frame = swipeHorizontalGuideFrame(progress)

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        val scale = min(
            size.width / HORIZONTAL_GUIDE_VIEWBOX_WIDTH,
            size.height / HORIZONTAL_GUIDE_VIEWBOX_HEIGHT,
        )
        val geometry = swipeHorizontalGuideGeometry(size.width, scale)
        val handLeft = geometry.centerHandLeft + frame.handOffsetFraction * geometry.handTravel
        val handTop = 18f * scale
        val touchPoint = Offset(
            x = handLeft + 26f * scale,
            y = handTop + 2f * scale,
        )
        val accent = guideAccent.copy(alpha = frame.trailAlpha)

        if (frame.trailAlpha > 0f) {
            val arrowLeft = geometry.arrowLeft
            val arrowRight = geometry.arrowRight
            val arrowY = touchPoint.y
            val strokeWidth = 3f * scale
            val dashLength = 6f * scale
            val dashGap = 5f * scale
            var dashLeft = arrowLeft + 8f * scale
            while (dashLeft + dashLength < arrowRight - 8f * scale) {
                drawLine(
                    color = accent,
                    start = Offset(dashLeft, arrowY),
                    end = Offset(dashLeft + dashLength, arrowY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                dashLeft += dashLength + dashGap
            }
            drawLine(
                color = accent,
                start = Offset(arrowLeft, arrowY),
                end = Offset(arrowLeft + 7f * scale, arrowY - 5f * scale),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent,
                start = Offset(arrowLeft, arrowY),
                end = Offset(arrowLeft + 7f * scale, arrowY + 5f * scale),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent,
                start = Offset(arrowRight, arrowY),
                end = Offset(arrowRight - 7f * scale, arrowY - 5f * scale),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent,
                start = Offset(arrowRight, arrowY),
                end = Offset(arrowRight - 7f * scale, arrowY + 5f * scale),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        if (frame.touchRingAlpha > 0f) {
            drawCircle(
                color = guideAccent.copy(alpha = frame.touchRingAlpha * 0.75f),
                radius = 6f * scale * frame.touchRingScale,
                center = touchPoint,
                style = Stroke(width = 2.5f * scale),
            )
        }

        val hand = handPath(scale)
        withTransform({ translate(left = handLeft, top = handTop) }) {
            drawPath(
                path = hand,
                color = GUIDE_HAND_FILL.copy(alpha = frame.handAlpha),
            )
            drawPath(
                path = hand,
                color = GUIDE_HAND_OUTLINE.copy(alpha = frame.handAlpha),
                style = Stroke(
                    width = 3.5f * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawLine(
                color = GUIDE_HAND_OUTLINE.copy(alpha = frame.handAlpha),
                start = Offset(33f * scale, 24f * scale),
                end = Offset(33f * scale, 34f * scale),
                strokeWidth = 2.5f * scale,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = GUIDE_HAND_OUTLINE.copy(alpha = frame.handAlpha),
                start = Offset(47f * scale, 29f * scale),
                end = Offset(47f * scale, 36f * scale),
                strokeWidth = 2.5f * scale,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun handPath(scale: Float): Path = Path().apply {
    moveTo(26f * scale, 1f * scale)
    cubicTo(21.5f * scale, 1f * scale, 19f * scale, 4.2f * scale, 19f * scale, 8.5f * scale)
    lineTo(19f * scale, 34.5f * scale)
    lineTo(14f * scale, 29.5f * scale)
    cubicTo(10.2f * scale, 25.7f * scale, 5.2f * scale, 26.3f * scale, 2.4f * scale, 30.3f * scale)
    cubicTo(-0.2f * scale, 34.1f * scale, 0.7f * scale, 38.8f * scale, 3.8f * scale, 42.4f * scale)
    lineTo(17f * scale, 57.8f * scale)
    cubicTo(20.4f * scale, 61.7f * scale, 25f * scale, 63.5f * scale, 30.2f * scale, 63.5f * scale)
    lineTo(45.3f * scale, 63.5f * scale)
    cubicTo(54.4f * scale, 63.5f * scale, 60.5f * scale, 56.8f * scale, 60.5f * scale, 47.7f * scale)
    lineTo(60.5f * scale, 32f * scale)
    cubicTo(60.5f * scale, 27.4f * scale, 57.5f * scale, 24.3f * scale, 53.4f * scale, 24.3f * scale)
    cubicTo(50.2f * scale, 24.3f * scale, 48.2f * scale, 26.3f * scale, 47.2f * scale, 29.4f * scale)
    lineTo(47.2f * scale, 26f * scale)
    cubicTo(47.2f * scale, 21.6f * scale, 44.3f * scale, 18.7f * scale, 40.3f * scale, 18.7f * scale)
    cubicTo(36.8f * scale, 18.7f * scale, 34.4f * scale, 21.1f * scale, 33.4f * scale, 24.2f * scale)
    lineTo(33.4f * scale, 8.5f * scale)
    cubicTo(33.4f * scale, 4.2f * scale, 30.5f * scale, 1f * scale, 26f * scale, 1f * scale)
    close()
}

private const val SWIPE_UP_GUIDE_DURATION_MS = 1_400
private const val SWIPE_HORIZONTAL_GUIDE_DURATION_MS = 1_700
private const val GUIDE_VIEWBOX_WIDTH = 68f
private const val VERTICAL_GUIDE_VIEWBOX_HEIGHT = 148f
private const val HORIZONTAL_GUIDE_VIEWBOX_WIDTH = 200f
private const val HORIZONTAL_GUIDE_VIEWBOX_HEIGHT = 84f
private const val HAND_WIDTH = 62f
private const val HORIZONTAL_HAND_TRAVEL = 66f
private val GUIDE_ACCENT_BLUE = Color(0xFF1976D2)
private val GUIDE_HAND_OUTLINE = Color(0xFF101318)
private val GUIDE_HAND_FILL = Color.White
