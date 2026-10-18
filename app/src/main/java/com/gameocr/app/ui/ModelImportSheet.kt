package com.gameocr.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gameocr.app.R

/**
 * Import progress and checks for one GGUF. Only the explicit buttons close it once the copy has
 * finished, because "Cancel" then removes the model that was just imported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelImportSheet(
    state: ModelImportState,
    trialPassed: Boolean,
    busy: Boolean,
    onTry: () -> Unit,
    onCancel: () -> Unit,
    onFinish: (String) -> Unit,
) {
    val context = LocalContext.current
    val failed = state.stage == ModelImportStage.FAILED
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || failed },
    )
    var name by rememberSaveable(state.entry?.id) { mutableStateOf(state.entry?.name.orEmpty()) }
    ModalBottomSheet(onDismissRequest = { if (failed) onCancel() }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.model_import_button), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.model_import_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            ImportCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val total = state.totalBytes
                    when {
                        state.stage == ModelImportStage.COPYING && total != null ->
                            LinearProgressIndicator(progress = { (state.copiedBytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                        state.stage == ModelImportStage.COPYING || state.stage == ModelImportStage.CHECKING ->
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else -> LinearProgressIndicator(progress = { if (failed) 0f else 1f }, modifier = Modifier.fillMaxWidth())
                    }
                    val copied = Formatter.formatFileSize(context, state.copiedBytes)
                    Text(
                        when (state.stage) {
                            ModelImportStage.COPYING -> stringResource(R.string.model_import_copying,
                                modelDownloadAmountText(state.copiedBytes, total ?: -1L))
                            ModelImportStage.CHECKING -> stringResource(R.string.model_import_checking)
                            else -> stringResource(R.string.model_import_copied, copied)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (failed) {
                ImportCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.model_import_failed), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error)
                        state.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            val entry = state.entry
            if (entry != null) {
                ImportCard {
                    Column {
                        CheckRow(stringResource(R.string.model_check_header), stringResource(R.string.model_check_valid))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CheckRow(stringResource(R.string.model_check_arch),
                            listOf(entry.architecture, entry.quantization).filter { it.isNotBlank() }.joinToString("  "))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CheckRow(stringResource(R.string.model_check_template), stringResource(R.string.model_check_template_found))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (trialPassed) {
                            CheckRow(stringResource(R.string.model_check_trial_passed), "")
                        } else {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.model_check_trial), Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = onTry, enabled = !busy, modifier = Modifier.height(36.dp),
                                        shape = RoundedCornerShape(8.dp)) {
                                        Text(stringResource(R.string.model_try_now), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.model_import_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.stage == ModelImportStage.CHECKING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.model_import_checking), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !busy || failed,
                    modifier = Modifier.height(52.dp).let { if (failed) it.weight(1f) else it },
                    shape = RoundedCornerShape(26.dp)) {
                    Text(stringResource(if (failed) android.R.string.ok else android.R.string.cancel))
                }
                if (!failed) {
                    Button(onClick = { onFinish(name) }, enabled = !busy && entry != null && name.isNotBlank(),
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) {
                        Text(stringResource(R.string.model_import_finish))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun CheckRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
