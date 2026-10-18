package com.gameocr.app.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.data.*
import kotlin.math.roundToInt

@Composable
internal fun GestureSettingsEditor(
    page: FloatingSettingsPage,
    bindings: Map<PresetGesture, GestureAction>,
    edgeEnabled: Boolean,
    edgeRight: Boolean,
    edgeHeight: Int,
    onEdgeChange: (Boolean, Boolean, Int) -> Unit,
    twistEnabled: Boolean,
    twistSensitivity: Int,
    twistAction: GestureAction?,
    tiltEnabled: Boolean,
    tiltSensitivity: Int,
    tiltAction: GestureAction?,
    onMotionChange: (Boolean, Boolean, Int, GestureAction?) -> Unit,
    onBindingChange: (PresetGesture, GestureAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page == FloatingSettingsPage.BINDINGS) {
            Text(stringResource(R.string.gesture_settings_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PresetGesture.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { gesture ->
                        Column(Modifier.weight(1f)) {
                            SectionCard(title = null, contentPadding = 10.dp) {
                                GestureIllustration(gesture)
                                Text(stringResource(gesture.labelRes), style = MaterialTheme.typography.bodyMedium)
                                SettingsOptionDropdown(
                                    label = stringResource(gesture.labelRes),
                                    showLabel = false,
                                    value = GestureBindings.normalize(bindings).getValue(gesture),
                                    options = GestureBindings.actions,
                                    optionLabel = { stringResource(it.labelRes) },
                                    onValueChange = { onBindingChange(gesture, it) },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Text(stringResource(R.string.gesture_wake_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.gesture_wake_tap_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val footerHeight = maxOf(48.dp, with(LocalDensity.current) {
                MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 2f
            })
            GestureWakePreview.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { method ->
                        val enabled = when (method) {
                            GestureWakePreview.TAP -> true
                            GestureWakePreview.EDGE -> edgeEnabled
                            GestureWakePreview.TWIST -> twistEnabled
                            GestureWakePreview.TILT -> tiltEnabled
                        }
                        val title = when (method) {
                            GestureWakePreview.TAP -> R.string.gesture_wake_tap
                            GestureWakePreview.EDGE -> R.string.gesture_edge_enabled
                            GestureWakePreview.TWIST -> R.string.gesture_wake_twist
                            GestureWakePreview.TILT -> R.string.gesture_wake_tilt
                        }
                        val sensitivity = if (method == GestureWakePreview.TILT) tiltSensitivity else twistSensitivity
                        val action = when (method) {
                            GestureWakePreview.TWIST -> twistAction
                            GestureWakePreview.TILT -> tiltAction
                            else -> null
                        }
                        Column(Modifier.weight(1f)) {
                            SectionCard(
                                title = null,
                                contentPadding = 10.dp,
                                modifier = Modifier.height(176.dp + footerHeight + 30.dp),
                                borderColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ) {
                                Box(Modifier.fillMaxWidth().height(176.dp)
                                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                                    .alpha(if (enabled) 1f else 0.4f),
                                    contentAlignment = Alignment.Center) {
                                    GestureWakeIllustration(method, sensitivity, edgeRight, action,
                                        modifier = Modifier.graphicsLayer(scaleX = 1.2f, scaleY = 1.2f))
                                }
                                Row(Modifier.fillMaxWidth().height(footerHeight),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(title), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    if (method == GestureWakePreview.TAP) {
                                        Text(stringResource(R.string.gesture_wake_default),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp))
                                    } else SettingsSwitch(checked = enabled, filledTrack = true) { next ->
                                        when (method) {
                                            GestureWakePreview.EDGE -> onEdgeChange(next, edgeRight, edgeHeight)
                                            GestureWakePreview.TWIST -> onMotionChange(true, next, twistSensitivity, twistAction)
                                            GestureWakePreview.TILT -> onMotionChange(false, next, tiltSensitivity, tiltAction)
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (edgeEnabled) {
                SectionCard(title = null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.gesture_edge_position), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        SettingsChoiceRow(Modifier.weight(1.2f)) {
                            listOf(false, true).forEach { right ->
                                SettingsChoiceButton(selected = edgeRight == right,
                                    onClick = { onEdgeChange(edgeEnabled, right, edgeHeight) }, label = {
                                        Text(stringResource(if (right) R.string.gesture_edge_right else R.string.gesture_edge_left))
                                    })
                            }
                        }
                    }
                    var height by remember(edgeHeight) { mutableFloatStateOf(edgeHeight.toFloat()) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.gesture_edge_height), style = MaterialTheme.typography.bodyMedium)
                        Text("${height.roundToInt()}%", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(value = height, onValueChange = { height = it }, valueRange = 10f..40f, steps = 14,
                        onValueChangeFinished = { onEdgeChange(edgeEnabled, edgeRight, height.roundToInt()) })
                    Text(stringResource(R.string.gesture_edge_description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (twistEnabled) MotionWakeSettings(true, twistEnabled, twistSensitivity, twistAction, onMotionChange)
            if (tiltEnabled) MotionWakeSettings(false, tiltEnabled, tiltSensitivity, tiltAction, onMotionChange)
        }
    }
}

/** Same 76 x 112 paths and 2.9-second touch / travel / lift sequence as the HTML. */
@Composable
private fun GestureIllustration(gesture: PresetGesture) {
    val path = remember(gesture) {
        Path().apply {
            when (gesture) {
                PresetGesture.UP -> { moveTo(38f, 85f); lineTo(38f, 32f) }
                PresetGesture.DOWN -> { moveTo(38f, 32f); lineTo(38f, 85f) }
                PresetGesture.LEFT -> { moveTo(59f, 59f); lineTo(17f, 59f) }
                PresetGesture.RIGHT -> { moveTo(17f, 59f); lineTo(59f, 59f) }
                PresetGesture.RIGHT_DOWN -> { moveTo(17f, 34f); lineTo(57f, 34f); quadTo(60f, 34f, 60f, 37f); lineTo(60f, 83f) }
                PresetGesture.DOWN_RIGHT -> { moveTo(17f, 34f); lineTo(17f, 80f); quadTo(17f, 83f, 20f, 83f); lineTo(59f, 83f) }
                PresetGesture.S -> { moveTo(54f, 35f); cubicTo(21f, 16f, 9f, 49f, 32f, 55f); cubicTo(72f, 64f, 54f, 99f, 20f, 81f) }
                PresetGesture.O -> { moveTo(38f, 37f); cubicTo(50.15f, 37f, 60f, 46.85f, 60f, 59f); cubicTo(60f, 71.15f, 50.15f, 81f, 38f, 81f); cubicTo(25.85f, 81f, 16f, 71.15f, 16f, 59f); cubicTo(16f, 46.85f, 25.85f, 37f, 38f, 37f) }
            }
        }
    }
    val measure = remember(path) { PathMeasure(path, false) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val trail = remember { Path() }
    val point = remember { FloatArray(2) }
    val origin = remember(measure) { FloatArray(2).also { measure.getPosTan(0f, it, null) } }
    var restart by remember { mutableIntStateOf(0) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(restart) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { phase = ((it - start) / 1_000_000L % 2900) / 2900f }
    }
    val colors = MaterialTheme.colorScheme
    val bg = colors.background.toArgb()
    val outline = colors.outline.toArgb()
    val detail = colors.outlineVariant.toArgb()
    val surface = colors.surface.toArgb()
    val description = stringResource(R.string.gesture_replay, stringResource(gesture.labelRes))
    Canvas(Modifier.fillMaxWidth().height(140.dp).clickable(onClickLabel = description) { restart++ }) {
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        val scale = minOf(size.width / 76f, size.height / 112f)
        canvas.translate((size.width - 76 * scale) / 2, (size.height - 112 * scale) / 2)
        canvas.scale(scale, scale)
        paint.pathEffect = null; paint.style = Paint.Style.FILL; paint.color = bg
        canvas.drawRoundRect(3f, 1f, 73f, 111f, 12f, 12f, paint)
        paint.style = Paint.Style.STROKE; paint.color = outline; paint.strokeWidth = 1.5f
        canvas.drawRoundRect(3f, 1f, 73f, 111f, 12f, 12f, paint)
        paint.style = Paint.Style.FILL; paint.color = detail
        canvas.drawRoundRect(29f, 7f, 47f, 10f, 1.5f, 1.5f, paint)
        canvas.drawRoundRect(27f, 103f, 49f, 105.5f, 1.2f, 1.2f, paint)
        paint.style = Paint.Style.STROKE; paint.color = outline; paint.alpha = 140; paint.strokeWidth = 1f
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 4f), 0f)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        val progress = ((phase - .14f) / .55f).coerceIn(0f, 1f)
        val distance = measure.length * progress
        val opacity = if (phase < .76f) 1f else (1f - (phase - .76f) / .16f).coerceIn(0f, 1f)
        measure.getPosTan(distance, point, null)
        trail.reset(); measure.getSegment((distance - measure.length * .38f).coerceAtLeast(0f), distance, trail, true)
        paint.color = 0xFF38A4F5.toInt(); paint.alpha = (opacity * 102).toInt(); paint.strokeWidth = 5f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(trail, paint)
        paint.style = Paint.Style.FILL; paint.color = surface
        canvas.drawCircle(origin[0], origin[1], 2.5f, paint)
        paint.style = Paint.Style.STROKE; paint.color = 0xFF259AF0.toInt(); paint.strokeWidth = 1.2f
        canvas.drawCircle(origin[0], origin[1], 2.5f, paint)
        paint.style = Paint.Style.FILL; paint.alpha = (opacity * 31).toInt()
        canvas.drawCircle(point[0], point[1], if (phase < .14f) 7 + phase * 25 else 10f, paint)
        paint.alpha = (opacity * 255).toInt()
        canvas.drawCircle(point[0], point[1], 5.4f, paint)
        paint.style = Paint.Style.STROKE; paint.color = surface; paint.alpha = (opacity * 255).toInt(); paint.strokeWidth = 1.3f
        canvas.drawCircle(point[0], point[1], 5.4f, paint)
        if (phase > .69f && phase < .88f) {
            paint.color = 0xFF259AF0.toInt(); paint.alpha = ((1f - (phase - .69f) / .19f) * 102).toInt(); paint.strokeWidth = 1f
            canvas.drawCircle(point[0], point[1], 6 + (phase - .69f) * 70, paint)
        }
        canvas.restoreToCount(save)
    }
}

@Composable
private fun MotionWakeSettings(
    twist: Boolean,
    enabled: Boolean,
    sensitivity: Int,
    action: GestureAction?,
    onChange: (Boolean, Boolean, Int, GestureAction?) -> Unit,
) {
    var level by remember(sensitivity) { mutableFloatStateOf(sensitivity.toFloat()) }
    val labels = listOf(R.string.gesture_sensitivity_1, R.string.gesture_sensitivity_2,
        R.string.gesture_sensitivity_3, R.string.gesture_sensitivity_4, R.string.gesture_sensitivity_5)
    SectionCard(title = stringResource(if (twist) R.string.gesture_wake_twist else R.string.gesture_wake_tilt)) {
        Text(stringResource(if (twist) R.string.gesture_wake_twist_hint else R.string.gesture_wake_tilt_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.gesture_trigger_function),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            SettingsOptionDropdown<GestureAction?>(
                label = stringResource(R.string.gesture_trigger_function), value = action,
                showLabel = false,
                modifier = Modifier.weight(1.2f),
                options = listOf(null) + GestureBindings.actions,
                optionLabel = { stringResource(it?.labelRes ?: R.string.gesture_draw_title) },
                onValueChange = { onChange(twist, enabled, sensitivity, it) },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.gesture_sensitivity), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(labels[level.roundToInt().coerceIn(1, 5) - 1]),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = level, onValueChange = { level = it }, valueRange = 1f..5f, steps = 3,
            onValueChangeFinished = { onChange(twist, enabled, level.roundToInt(), action) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.gesture_sensitivity_1), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.gesture_sensitivity_5), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.gesture_sensitivity_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
