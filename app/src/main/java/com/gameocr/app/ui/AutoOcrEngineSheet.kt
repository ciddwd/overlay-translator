package com.gameocr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.OcrEngineKind

@Composable
internal fun autoOcrRouteLabel(route: AutoOcrRoute?): String = stringResource(autoOcrRouteLabelRes(route))

@androidx.annotation.StringRes
internal fun autoOcrRouteLabelRes(route: AutoOcrRoute?): Int = when {
    route == null -> R.string.settings_translation_preset_import_none
    route.engine == OcrEngineKind.PADDLE_ONNX -> route.paddleVersion.displayNameRes
    else -> ocrEngineLabelRes(route.engine)
}

/** Same sheet surface and expansion behavior as LanguagePickerSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoOcrEngineSheet(
    title: String,
    current: AutoOcrRoute?,
    options: List<AutoOcrRoute?>,
    state: (AutoOcrRoute?) -> AutoOcrOptionState,
    canSelect: (AutoOcrRoute?, AutoOcrOptionState) -> Boolean,
    downloadBusy: Boolean,
    onSelect: (AutoOcrRoute?) -> Unit,
    onDownload: (AutoOcrRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_close))
                }
            }
            LazyColumn(modifier = Modifier.selectableGroup(), contentPadding = PaddingValues(bottom = 16.dp)) {
                AutoOcrOptionGroup.entries.forEach { group ->
                    val groupOptions = options.filter { autoOcrOptionGroup(it) == group }
                    if (groupOptions.isNotEmpty()) {
                        item(key = "group:$group") {
                            if (group != AutoOcrOptionGroup.ON_DEVICE) {
                                HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Text(stringResource(when (group) {
                                AutoOcrOptionGroup.ON_DEVICE -> R.string.settings_auto_ocr_group_on_device
                                AutoOcrOptionGroup.LOCAL -> R.string.settings_auto_ocr_group_local
                                AutoOcrOptionGroup.CLOUD -> R.string.settings_auto_ocr_group_cloud
                            }), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(groupOptions, key = { "option:${it?.engine}:${it?.paddleVersion}" }) { route ->
                            val optionState = state(route)
                            val selectable = canSelect(route, optionState)
                            val selected = route == current
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                                        else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f)
                                        .selectable(selected = selected, enabled = selectable,
                                            role = Role.RadioButton, onClick = { onSelect(route) })
                                        .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, enabled = selectable, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(autoOcrRouteLabel(route), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selectable) 1f else 0.38f),
                                        modifier = Modifier.weight(1f))
                                }
                                when (optionState) {
                                    AutoOcrOptionState.DOWNLOAD -> TextButton(enabled = !downloadBusy,
                                        onClick = { route?.let(onDownload) },
                                    ) { Text(stringResource(R.string.settings_mlkit_model_download_short)) }
                                    AutoOcrOptionState.UNSUPPORTED, AutoOcrOptionState.UNCONFIGURED -> Text(
                                        stringResource(if (optionState == AutoOcrOptionState.UNSUPPORTED)
                                            R.string.settings_auto_ocr_unsupported else R.string.settings_auto_ocr_unconfigured),
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AutoOcrOptionState.CHECKING, AutoOcrOptionState.DOWNLOADING ->
                                        CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(18.dp), strokeWidth = 2.dp)
                                    AutoOcrOptionState.READY -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
