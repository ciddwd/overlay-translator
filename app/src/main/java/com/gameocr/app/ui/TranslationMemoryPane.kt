package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.translate.TranslationMemoryEntity

@Composable
internal fun TranslationMemoryPane(
    entries: List<TranslationMemoryEntity>,
    query: String,
    onUpdate: (id: Long, correctedSource: String, correctedTranslation: String) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    var editing by remember { mutableStateOf<TranslationMemoryEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<TranslationMemoryEntity?>(null) }
    val visibleEntries = remember(entries, query) {
        TranslationMemoryListFilterPolicy.filter(entries, query)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visibleEntries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (entries.isEmpty()) {
                                R.string.translation_memory_empty
                            } else {
                                R.string.translation_memory_filter_empty
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(visibleEntries, key = TranslationMemoryEntity::id) { entry ->
                TranslationMemoryCard(
                    entry = entry,
                    onEdit = { editing = entry },
                    onDelete = { pendingDelete = entry },
                )
            }
        }
    }

    editing?.let { entry ->
        TranslationMemoryEditor(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { source, translation ->
                onUpdate(entry.id, source, translation)
                editing = null
            },
        )
    }

    pendingDelete?.let { entry ->
        TranslationMemoryDeleteDialog(
            entry = entry,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDelete(entry.id)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun TranslationMemoryCard(
    entry: TranslationMemoryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val appLabel = entry.appLabel.ifBlank { entry.scopePackage }
    val sourceLanguage = Languages.nameOf(context, entry.sourceLang)
    val targetLanguage = Languages.nameOf(context, entry.targetLang)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.correctedSource,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.correctedTranslation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.observedSource != entry.correctedSource) {
                    Text(
                        text = stringResource(
                            R.string.translation_memory_observed_source_format,
                            entry.observedSource,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$sourceLanguage -> $targetLanguage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, stringResource(R.string.translation_memory_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.translation_memory_delete))
            }
        }
    }
}

@Composable
private fun TranslationMemoryEditor(
    entry: TranslationMemoryEntity,
    onDismiss: () -> Unit,
    onSave: (correctedSource: String, correctedTranslation: String) -> Unit,
) {
    var correctedSource by remember(entry) { mutableStateOf(entry.correctedSource) }
    var correctedTranslation by remember(entry) { mutableStateOf(entry.correctedTranslation) }
    val canSave = correctedSource.isNotBlank() && correctedTranslation.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.heightIn(max = 640.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.translation_memory_edit),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.settings_color_cancel))
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.translation_memory_observed_source),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        entry.observedSource,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = correctedSource,
                        onValueChange = { correctedSource = it },
                        label = { Text(stringResource(R.string.translation_memory_corrected_source)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                    OutlinedTextField(
                        value = correctedTranslation,
                        onValueChange = { correctedTranslation = it },
                        label = { Text(stringResource(R.string.translation_memory_corrected_translation)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_color_cancel))
                    }
                    Button(
                        enabled = canSave,
                        onClick = { onSave(correctedSource, correctedTranslation) },
                    ) {
                        Text(stringResource(R.string.translation_memory_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationMemoryDeleteDialog(
    entry: TranslationMemoryEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.translation_memory_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
                HorizontalDivider()
                Text(
                    text = stringResource(
                        R.string.translation_memory_delete_confirm_message,
                        entry.correctedSource,
                        entry.correctedTranslation,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp),
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_color_cancel))
                    }
                    DestructiveTextButton(
                        label = stringResource(R.string.translation_memory_delete),
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}
