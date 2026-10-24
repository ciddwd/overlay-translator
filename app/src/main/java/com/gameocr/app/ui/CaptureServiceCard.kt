package com.gameocr.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureStartMode

internal enum class CaptureSetupStepState { TODO, ACTIVE, DONE }

internal enum class CaptureMethodCheck { WAITING, CHECKING, SELECTED, UNAVAILABLE, SKIPPED, NEEDS_ANDROID_11, NOT_NEEDED }

/** Fixed display order of the one-button method check. Never reorder. */
internal val captureSetupCheckOrder = listOf(
    CaptureStartMode.ROOT,
    CaptureStartMode.SHIZUKU,
    CaptureStartMode.ACCESSIBILITY,
    CaptureStartMode.SYSTEM,
)

internal data class CaptureSetupUiState(
    val overlay: CaptureSetupStepState = CaptureSetupStepState.TODO,
    val method: CaptureSetupStepState = CaptureSetupStepState.TODO,
    val start: CaptureSetupStepState = CaptureSetupStepState.TODO,
    val checks: Map<CaptureStartMode, CaptureMethodCheck> =
        captureSetupCheckOrder.associateWith { CaptureMethodCheck.WAITING },
    val resolved: CaptureStartMode? = null,
    val overlayViaShizuku: Boolean = false,
    val overlayViaRoot: Boolean = false,
    val busy: Boolean = false,
)

internal data class CaptureSetupMethodResult(
    val checks: Map<CaptureStartMode, CaptureMethodCheck>,
    val resolved: CaptureStartMode,
)

/**
 * One-button setup resolves the method from the same session choice the launch reads.
 * An explicit choice wins when it can work; otherwise (and for Auto) the fixed order applies:
 * Root, then Shizuku, then Accessibility, then System capture. The resolved method is passed to
 * the launch as a one-time override, so the setup and the launch can never disagree.
 * [rootGranted] is true only after root was actually granted for this session.
 */
internal fun captureSetupMethodResult(
    preferred: CaptureStartMode?,
    rootGranted: Boolean,
    shizukuUsable: Boolean,
    sdk: Int,
    accessibilityEnabled: Boolean,
): CaptureSetupMethodResult {
    val nn = CaptureMethodCheck.NOT_NEEDED
    fun result(
        root: CaptureMethodCheck,
        shizuku: CaptureMethodCheck,
        a11y: CaptureMethodCheck,
        system: CaptureMethodCheck,
        resolved: CaptureStartMode,
    ) = CaptureSetupMethodResult(
        mapOf(
            CaptureStartMode.ROOT to root,
            CaptureStartMode.SHIZUKU to shizuku,
            CaptureStartMode.ACCESSIBILITY to a11y,
            CaptureStartMode.SYSTEM to system,
        ),
        resolved,
    )
    if (preferred == CaptureStartMode.SYSTEM) {
        return result(nn, nn, nn, CaptureMethodCheck.SELECTED, CaptureStartMode.SYSTEM)
    }
    if (preferred == CaptureStartMode.ACCESSIBILITY && sdk >= 30) {
        return when {
            accessibilityEnabled -> result(nn, nn, CaptureMethodCheck.SELECTED, nn, CaptureStartMode.ACCESSIBILITY)
            else -> result(nn, nn, CaptureMethodCheck.SKIPPED, CaptureMethodCheck.SELECTED,
                CaptureStartMode.SYSTEM)
        }
    }
    // An explicit Shizuku choice skips Root; Auto and an explicit Root start with Root.
    val rootCheck = when {
        preferred == CaptureStartMode.SHIZUKU -> nn
        rootGranted -> return result(CaptureMethodCheck.SELECTED, nn, nn, nn, CaptureStartMode.ROOT)
        else -> CaptureMethodCheck.UNAVAILABLE
    }
    return when {
        shizukuUsable -> result(rootCheck, CaptureMethodCheck.SELECTED, nn, nn, CaptureStartMode.SHIZUKU)
        sdk < 30 -> result(rootCheck, CaptureMethodCheck.UNAVAILABLE, CaptureMethodCheck.NEEDS_ANDROID_11,
            CaptureMethodCheck.SELECTED, CaptureStartMode.SYSTEM)
        accessibilityEnabled -> result(rootCheck, CaptureMethodCheck.UNAVAILABLE, CaptureMethodCheck.SELECTED,
            nn, CaptureStartMode.ACCESSIBILITY)
        else -> result(rootCheck, CaptureMethodCheck.UNAVAILABLE, CaptureMethodCheck.SKIPPED,
            CaptureMethodCheck.SELECTED, CaptureStartMode.SYSTEM)
    }
}

internal fun captureMethodCheckLabelRes(check: CaptureMethodCheck): Int = when (check) {
    CaptureMethodCheck.WAITING -> R.string.main_check_waiting
    CaptureMethodCheck.CHECKING -> R.string.main_check_checking
    CaptureMethodCheck.SELECTED -> R.string.main_check_selected
    CaptureMethodCheck.UNAVAILABLE -> R.string.main_check_unavailable
    CaptureMethodCheck.SKIPPED -> R.string.main_check_skipped
    CaptureMethodCheck.NEEDS_ANDROID_11 -> R.string.main_check_android11
    CaptureMethodCheck.NOT_NEEDED -> R.string.main_check_not_needed
}

@Composable
internal fun CaptureServiceCard(
    modifier: Modifier,
    showSetup: Boolean,
    setup: CaptureSetupUiState,
    startAvailable: Boolean,
    serviceRunning: Boolean,
    overlayGranted: Boolean,
    batteryOk: Boolean,
    modeLabel: String,
    methodValue: String,
    usageText: String,
    onRunSetup: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenMethodSheet: () -> Unit,
    onFixOverlay: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenHelp: () -> Unit,
    onNaturalHeightChanged: (Int) -> Unit = {},
) {
    Card(
        modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            // Measure content independently of the shared carousel height.
            modifier = Modifier
                .wrapContentHeight(unbounded = true)
                .onSizeChanged { onNaturalHeightChanged(it.height) }
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CaptureCardHeader(showSetup, setup.busy, serviceRunning, startAvailable, modeLabel)
            if (showSetup) {
                CaptureSetupSteps(setup)
                PrimaryActionButton(
                    label = stringResource(
                        when {
                            setup.busy -> R.string.main_setup_button_busy
                            setup.overlay == CaptureSetupStepState.DONE -> R.string.main_setup_button_continue
                            else -> R.string.main_setup_button
                        }
                    ),
                    busy = setup.busy,
                    onClick = onRunSetup,
                )
            } else {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KeyStatusTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Layers,
                        label = stringResource(R.string.main_key_overlay),
                        value = stringResource(
                            if (overlayGranted) R.string.main_key_overlay_ok else R.string.main_key_overlay_needed
                        ),
                        ok = overlayGranted,
                        onClick = onFixOverlay,
                    )
                    KeyStatusTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.CenterFocusWeak,
                        label = stringResource(R.string.main_capture_method),
                        value = methodValue,
                        ok = true,
                        onClick = onOpenMethodSheet,
                    )
                    KeyStatusTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.BatteryChargingFull,
                        label = stringResource(R.string.main_key_background),
                        value = stringResource(
                            if (batteryOk) R.string.main_key_background_ok else R.string.main_key_background_risk
                        ),
                        ok = batteryOk,
                        onClick = onFixBattery,
                    )
                }
                AnimatedContent(
                    targetState = serviceRunning,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "capture-start-stop",
                ) { running ->
                    if (running) {
                        Button(
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            onClick = onStop,
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text(stringResource(R.string.main_action_stop), modifier = Modifier.padding(start = 8.dp))
                        }
                    } else if (!startAvailable) {
                        Button(modifier = Modifier.fillMaxWidth().height(56.dp), onClick = onFixOverlay) {
                            Text(stringResource(R.string.main_action_grant_overlay_first))
                        }
                    } else {
                        Button(modifier = Modifier.fillMaxWidth().height(56.dp), onClick = onStart) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(
                                stringResource(R.string.main_action_start_format, modeLabel),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            UsageRow(usageText = usageText, onClick = onOpenHelp)
        }
    }
}

@Composable
private fun CaptureCardHeader(showSetup: Boolean, busy: Boolean, running: Boolean, startAvailable: Boolean, modeLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                stringResource(R.string.main_section_capture),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    showSetup -> stringResource(R.string.main_capture_sub_setup)
                    running -> stringResource(R.string.main_capture_sub_running, modeLabel)
                    else -> stringResource(R.string.main_capture_sub_ready)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CaptureStatusChip(
            label = stringResource(
                when {
                    showSetup && busy -> R.string.main_capture_status_setting_up
                    showSetup -> R.string.main_capture_status_setup
                    running -> R.string.main_capture_status_running
                    !startAvailable -> R.string.main_key_overlay_needed
                    else -> R.string.main_capture_status_ready
                }
            ),
            tone = when {
                showSetup || !startAvailable -> ChipTone.WARN
                running -> ChipTone.OK
                else -> ChipTone.NEUTRAL
            },
        )
    }
}

private enum class ChipTone { NEUTRAL, OK, WARN }

@Composable
private fun chipColors(tone: ChipTone): Pair<Color, Color> = when (tone) {
    ChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
    ChipTone.OK -> HomeStatusColors.okContainer() to HomeStatusColors.ok()
    ChipTone.WARN -> HomeStatusColors.warnContainer() to HomeStatusColors.warn()
}

/** Status colors for chips only; the app stays on its Slate palette everywhere else. */
internal object HomeStatusColors {
    @Composable fun dark() = MaterialTheme.colorScheme.background.luminance() < 0.5f
    @Composable fun ok() = if (dark()) Color(0xFF4ADE80) else Color(0xFF15803D)
    @Composable fun okContainer() = if (dark()) Color(0xFF14532D) else Color(0xFFDCFCE7)
    @Composable fun warn() = if (dark()) Color(0xFFFBBF24) else Color(0xFFB45309)
    @Composable fun warnContainer() = if (dark()) Color(0xFF422006) else Color(0xFFFEF3C7)
}

@Composable
private fun CaptureStatusChip(label: String, tone: ChipTone) {
    val (container, content) = chipColors(tone)
    val animatedContainer by animateColorAsState(container, tween(300), label = "chip-container")
    val animatedContent by animateColorAsState(content, tween(300), label = "chip-content")
    val pulse = rememberInfiniteTransition(label = "chip-pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "chip-pulse-scale",
    )
    Row(
        modifier = Modifier
            .background(animatedContainer, RoundedCornerShape(13.dp))
            .heightIn(min = 26.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (tone == ChipTone.OK) {
                Box(
                    Modifier
                        .size(7.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = (2.2f - pulseScale) / 1.2f * 0.5f
                        }
                        .background(animatedContent, CircleShape)
                )
            }
            Box(Modifier.size(7.dp).background(animatedContent, CircleShape))
        }
        Text(
            label,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = animatedContent,
        )
    }
}

@Composable
private fun KeyStatusTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    ok: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight().heightIn(min = 76.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = ok,
                    transitionSpec = { scaleIn(spring(dampingRatio = 0.5f)) togetherWith fadeOut(tween(120)) },
                    label = "tile-badge",
                ) { good ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(
                                if (good) HomeStatusColors.okContainer() else HomeStatusColors.warnContainer(),
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (good) Icons.Default.Check else Icons.Default.PriorityHigh,
                            contentDescription = null,
                            tint = if (good) HomeStatusColors.ok() else HomeStatusColors.warn(),
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (ok) MaterialTheme.colorScheme.onSurface else HomeStatusColors.warn(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        enabled = !busy,
        onClick = onClick,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
        }
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun CaptureSetupSteps(setup: CaptureSetupUiState) {
    Column {
        SetupStepRow(1, Icons.Outlined.CenterFocusWeak,
            stringResource(R.string.main_setup_detect_title), "", setup.method)
        SetupStepRow(2, Icons.Outlined.Layers,
            stringResource(R.string.main_setup_overlay_title), "", setup.overlay)
        SetupStepRow(3, Icons.Outlined.PlayArrow,
            stringResource(R.string.main_setup_start_title), "", setup.start)
    }
}

@Composable
private fun SetupStepRow(
    number: Int,
    icon: ImageVector,
    title: String,
    detail: String,
    state: CaptureSetupStepState,
    extra: @Composable () -> Unit = {},
) {
    val rowBg by animateColorAsState(
        if (state == CaptureSetupStepState.ACTIVE) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
        tween(300), label = "step-row",
    )
    val tileBg by animateColorAsState(
        if (state == CaptureSetupStepState.DONE) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainer,
        tween(300), label = "step-tile",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(tileBg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { scaleIn(spring(dampingRatio = 0.5f)) + fadeIn() togetherWith fadeOut(tween(120)) },
                label = "step-icon",
            ) { current ->
                when (current) {
                    CaptureSetupStepState.DONE -> Icon(Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    CaptureSetupStepState.ACTIVE -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    CaptureSetupStepState.TODO -> Icon(icon, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (state == CaptureSetupStepState.TODO) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.main_setup_step_format, number, 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
    }
}

@Composable
private fun UsageRow(usageText: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 52.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.main_label_usage), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(usageText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.main_help), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

