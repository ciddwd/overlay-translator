package com.gameocr.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Stable
private class SettingsChoiceIndicator {
    var container: LayoutCoordinates? = null
    var bounds by mutableStateOf<IntRect?>(null)
    var enabled by mutableStateOf(true)

    fun select(coordinates: LayoutCoordinates?, enabled: Boolean) {
        val parent = container ?: return
        if (!parent.isAttached || coordinates?.isAttached != true) return
        val origin = parent.localPositionOf(coordinates, Offset.Zero)
        val x = origin.x.roundToInt()
        val y = origin.y.roundToInt()
        bounds = IntRect(x, y, x + coordinates.size.width, y + coordinates.size.height)
        this.enabled = enabled
    }
}

private val LocalChoiceIndicator = staticCompositionLocalOf<SettingsChoiceIndicator?> { null }

/** One shared selection style for settings; labels and actions stay with their callers. */
@Composable
internal fun SettingsChoiceRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val indicator = remember { SettingsChoiceIndicator() }
    Box(
        modifier = modifier.height(IntrinsicSize.Min).selectableGroup()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .onGloballyPositioned { indicator.container = it },
    ) {
        indicator.bounds?.let { bounds ->
            val position by animateIntOffsetAsState(
                IntOffset(bounds.left, bounds.top), tween(220, easing = FastOutSlowInEasing), label = "choicePosition",
            )
            val size by animateIntSizeAsState(bounds.size, tween(220, easing = FastOutSlowInEasing), label = "choiceSize")
            val density = LocalDensity.current
            // An overlay keeps the animated indicator out of the row's intrinsic measurement.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.absoluteOffset { position }
                    .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                    .alpha(if (indicator.enabled) 1f else 0.38f)
                    .shadow(1.dp, RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9.dp)))
            }
        }
        CompositionLocalProvider(LocalChoiceIndicator provides indicator) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
internal fun RowScope.SettingsChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    weight: Float = 1f,
    label: @Composable () -> Unit,
) {
    val indicator = LocalChoiceIndicator.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    SideEffect { if (selected) indicator?.select(coordinates, enabled) }
    Box(
        modifier = modifier.weight(weight).fillMaxHeight().heightIn(min = 36.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .onGloballyPositioned {
                coordinates = it
                if (selected) indicator?.select(it, enabled)
            }
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.W700 else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )) { label() }
        }
    }
}
