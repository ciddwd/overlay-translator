package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.capture.CaptureStartMode
import com.gameocr.app.capture.RootAccess
import com.gameocr.app.capture.rootUsableForAuto
import com.gameocr.app.capture.isShizukuUsableForCapture
import com.gameocr.app.shizuku.ShizukuCapabilities

/** Auto first, then the fixed fallback order. Never reorder: Root, Shizuku, Accessibility, System capture. */
internal val captureMethodSheetOptions: List<CaptureStartMode?> = listOf(
    null,
    CaptureStartMode.ROOT,
    CaptureStartMode.SHIZUKU,
    CaptureStartMode.ACCESSIBILITY,
    CaptureStartMode.SYSTEM,
)

internal fun captureStartModeHintRes(
    mode: CaptureStartMode,
    auto: Boolean,
    shizukuAvailability: ShizukuCapabilities.Availability,
    accessibilityEnabled: Boolean,
    rootAccess: RootAccess,
): Int = when (mode) {
    CaptureStartMode.ROOT -> rootHintRes(rootAccess)
    CaptureStartMode.SHIZUKU -> shizukuHintRes(shizukuAvailability)
    CaptureStartMode.ACCESSIBILITY ->
        if (accessibilityEnabled) R.string.main_capture_a11y_ready else R.string.main_capture_a11y_off
    CaptureStartMode.SYSTEM ->
        if (auto) R.string.main_hint_media_projection else R.string.main_capture_system_desc
}

internal fun rootHintRes(access: RootAccess): Int = when (access) {
    RootAccess.GRANTED -> R.string.main_capture_root_ready
    RootAccess.UNKNOWN -> R.string.main_capture_root_available
    RootAccess.DENIED -> R.string.main_capture_root_denied
    RootAccess.NOT_INSTALLED -> R.string.main_capture_root_not_found
}

internal fun shizukuHintRes(availability: ShizukuCapabilities.Availability): Int = when (availability) {
    ShizukuCapabilities.Availability.READY -> R.string.main_hint_shizuku_ready
    ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED -> R.string.main_hint_shizuku_not_granted
    ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED -> R.string.main_hint_shizuku_not_paired
    ShizukuCapabilities.Availability.INSTALLED_COMPATIBILITY_REQUIRED ->
        R.string.main_hint_shizuku_compatibility_required
    ShizukuCapabilities.Availability.NOT_RUNNING -> R.string.main_check_unavailable
    ShizukuCapabilities.Availability.NOT_INSTALLED -> R.string.main_hint_shizuku_not_installed
}

internal fun captureMethodOptionEnabled(
    option: CaptureStartMode?,
    shizukuAvailability: ShizukuCapabilities.Availability,
    sdk: Int,
    rootAccess: RootAccess,
): Boolean = when (option) {
    null, CaptureStartMode.SYSTEM -> true
    CaptureStartMode.ROOT -> rootUsableForAuto(rootAccess)
    CaptureStartMode.SHIZUKU -> isShizukuUsableForCapture(shizukuAvailability)
    CaptureStartMode.ACCESSIBILITY -> sdk >= 30
}

@Composable
internal fun captureStartModeLabel(mode: CaptureStartMode): String = when (mode) {
    CaptureStartMode.ROOT -> stringResource(R.string.main_capture_root)
    CaptureStartMode.SHIZUKU -> "Shizuku"
    CaptureStartMode.ACCESSIBILITY -> stringResource(R.string.main_capture_accessibility)
    CaptureStartMode.SYSTEM -> stringResource(R.string.main_capture_system)
}

@Composable
internal fun CaptureMethodRow(
    value: String,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.CenterFocusWeak,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.main_capture_method),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Icon(
                if (locked) Icons.Default.Lock else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(if (locked) 16.dp else 20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureMethodSheet(
    selected: CaptureStartMode?,
    autoResolved: CaptureStartMode,
    shizukuAvailability: ShizukuCapabilities.Availability,
    accessibilityEnabled: Boolean,
    rootAccess: RootAccess,
    sdk: Int,
    locked: Boolean,
    onSelect: (CaptureStartMode?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.main_capture_method),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_close))
                }
            }
            if (locked) {
                Text(
                    stringResource(R.string.main_capture_method_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                captureMethodSheetOptions.forEach { option ->
                    val enabled = !locked && captureMethodOptionEnabled(option, shizukuAvailability, sdk, rootAccess)
                    CaptureMethodOption(
                        title = when (option) {
                            null -> stringResource(R.string.main_capture_auto)
                            else -> captureStartModeLabel(option)
                        },
                        detail = when (option) {
                            null -> stringResource(R.string.main_capture_auto_desc) + " " +
                                stringResource(R.string.main_capture_auto_now, captureStartModeLabel(autoResolved))
                            CaptureStartMode.ACCESSIBILITY ->
                                if (sdk < 30) stringResource(R.string.main_capture_a11y_unsupported)
                                else stringResource(
                                    captureStartModeHintRes(option, false, shizukuAvailability, accessibilityEnabled, rootAccess)
                                )
                            else -> stringResource(
                                captureStartModeHintRes(option, false, shizukuAvailability, accessibilityEnabled, rootAccess)
                            )
                        },
                        recommended = option == null,
                        selected = option == selected,
                        enabled = enabled,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureMethodOption(
    title: String,
    detail: String,
    recommended: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled || selected) 1f else 0.45f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    )
                    if (recommended) {
                        Text(
                            stringResource(R.string.main_capture_recommended),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    detail,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
    }
}
