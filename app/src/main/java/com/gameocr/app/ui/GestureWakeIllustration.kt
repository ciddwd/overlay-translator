package com.gameocr.app.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gameocr.app.R
import com.gameocr.app.data.GestureAction
import com.gameocr.app.data.GestureMotionSensitivity
import kotlin.math.PI
import kotlin.math.cos

internal enum class GestureWakePreview { TAP, EDGE, TWIST, TILT }

/** Phone movements, touch point and capture transition match the HTML's 3.8-second sequence. */
@Composable
internal fun GestureWakeIllustration(
    method: GestureWakePreview,
    sensitivity: Int,
    right: Boolean,
    action: GestureAction?,
    modifier: Modifier = Modifier,
) {
    val drawGesture = action == null
    var restart by remember { mutableIntStateOf(0) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(restart) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { phase = ((it - start) / 1_000_000L % 3800) / 3800f }
    }
    val colors = MaterialTheme.colorScheme
    val bg = colors.background.toArgb()
    val outline = colors.outline.toArgb()
    val detail = colors.outlineVariant.toArgb()
    val foreground = colors.onSurfaceVariant.toArgb()
    val primary = colors.primary.toArgb()
    val onPrimary = colors.onPrimary.toArgb()
    val surface = colors.surface.toArgb()
    val context = LocalContext.current
    val logo = remember(context) { requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_gesture_ball)).mutate() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val trail = remember { Path() }
    val angle = GestureMotionSensitivity.degrees(sensitivity)
    val turn = when (method) {
        GestureWakePreview.TWIST -> movement(phase, floatArrayOf(.12f,.23f,.33f,.43f,.53f,.60f), floatArrayOf(0f,-angle,angle,-angle,angle,0f))
        GestureWakePreview.TILT -> movement(phase, floatArrayOf(.12f,.23f,.34f,.45f,.56f), floatArrayOf(0f,angle,0f,angle,0f))
        else -> 0f
    }
    val label = stringResource(when (method) {
        GestureWakePreview.TAP -> R.string.gesture_wake_tap
        GestureWakePreview.EDGE -> R.string.gesture_edge_enabled
        GestureWakePreview.TWIST -> R.string.gesture_wake_twist
        GestureWakePreview.TILT -> R.string.gesture_wake_tilt
    })
    val ready = when {
        phase < .56f -> 0f
        phase < .62f -> (phase - .56f) / .06f
        phase < .86f -> 1f
        else -> (1f - (phase - .86f) / .10f).coerceIn(0f, 1f)
    }
    Box(modifier.size(76.dp, 126.dp).clickable(onClickLabel = label) { restart++ }) {
        Canvas(Modifier.size(76.dp, 112.dp).graphicsLayer {
            rotationY = if (method == GestureWakePreview.TWIST) turn else 0f
            rotationX = if (method == GestureWakePreview.TILT) turn else 0f
            cameraDistance = 340 * density
        }) {
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            canvas.scale(size.width / 76f, size.height / 112f)
            paint.style = Paint.Style.FILL; paint.alpha = 255; paint.color = detail
            canvas.drawRoundRect(8f, 3f, 72f, 111f, 11f, 11f, paint)
            paint.color = bg
            canvas.drawRoundRect(6f, 1f, 70f, 109f, 11f, 11f, paint)
            paint.style = Paint.Style.STROKE; paint.color = outline; paint.strokeWidth = 1.5f
            canvas.drawRoundRect(6f, 1f, 70f, 109f, 11f, 11f, paint)
            paint.style = Paint.Style.FILL; paint.color = detail
            canvas.drawRoundRect(30f, 6f, 44f, 8.5f, 1.2f, 1.2f, paint)
            canvas.drawRoundRect(28f, 102f, 46f, 104f, 1f, 1f, paint)
            paint.color = foreground; paint.textSize = 10f; paint.typeface = Typeface.DEFAULT
            canvas.drawText("9:41", 14f, 27f, paint)
            paint.color = detail; paint.alpha = 166
            for (row in 0..2) for (col in 0..2) {
                val x = 14f + col * 17; val y = 38f + row * 17
                canvas.drawRoundRect(x, y, x + 12, y + 12, 3f, 3f, paint)
            }
            paint.alpha = 255
            if (method == GestureWakePreview.EDGE) {
                paint.color = 0x66259AF0
                val x = if (right) 65f else 8f
                canvas.drawRoundRect(x, 39f, x + 3, 69f, 1.5f, 1.5f, paint)
            }
            paint.color = primary
            canvas.drawCircle(60.5f, 68.5f, 6.5f, paint)
            logo.setTint(onPrimary); logo.setBounds(56,64,65,73); logo.draw(canvas)
            val capturing = if (phase < .54f) 0f else if (phase < .60f) (phase-.54f)/.06f else if (phase < .87f) 1f else ((.97f-phase)/.10f).coerceIn(0f,1f)
            if (capturing > 0 && drawGesture) {
                paint.color = foreground; paint.alpha = (capturing * 24).toInt()
                canvas.drawRoundRect(8f, 11f, 68f, 99f, 6f, 6f, paint)
                paint.color = surface; paint.alpha = (capturing*255).toInt()
                canvas.drawRoundRect(12f,16f,64f,26f,3f,3f,paint)
                paint.style = Paint.Style.STROKE; paint.color = 0xFFB4B0FA.toInt(); paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
                trail.reset(); trail.moveTo(28f, 43f); trail.lineTo(28f, 70f); trail.quadTo(28f,75f,33f,75f); trail.lineTo(48f,75f)
                canvas.drawPath(trail,paint); paint.style = Paint.Style.FILL; paint.alpha = 255
            }
            if ((method == GestureWakePreview.TAP || method == GestureWakePreview.EDGE) && phase in .14f.. .55f) {
                val progress = ((phase-.14f)/.28f).coerceIn(0f,1f)
                val x = if (method == GestureWakePreview.TAP) 60.5f+(1-progress)*12 else if (right) 69-progress*28 else 7+progress*28
                val y = if (method == GestureWakePreview.TAP) 68.5f+(1-progress)*16 else 50f
                paint.color = 0xFF259AF0.toInt(); paint.alpha = 30
                canvas.drawCircle(x,y,9f,paint); paint.alpha = 255
                canvas.drawCircle(x,y,4.5f,paint)
            }
            canvas.restoreToCount(save)
        }
        Text(stringResource(when (action) {
            null -> R.string.gesture_wake_ready
            GestureAction.NONE -> R.string.gesture_unbound_title
            else -> R.string.gesture_wake_executed
        }),
            fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFF259AF0),
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = ready })
    }
}

private fun movement(phase: Float, times: FloatArray, values: FloatArray): Float {
    if (phase <= times.first() || phase >= times.last()) return 0f
    val index = (1 until times.size).first { phase <= times[it] }
    val t = (phase - times[index-1]) / (times[index]-times[index-1])
    val eased = ((1-cos(t*PI))/2).toFloat()
    return values[index-1] + (values[index]-values[index-1])*eased
}
