package com.gameocr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.translate.MlKitDownloadPhase
import com.gameocr.app.translate.MlKitModelDownloadSession
import com.gameocr.app.translate.MlKitModelDownloadState

@Composable
internal fun rememberMlKitModelDownloadSession(
    download: suspend (Pair<String, String>) -> Unit,
): MlKitModelDownloadSession {
    val scope = rememberCoroutineScope()
    val currentDownload by rememberUpdatedState(download)
    val session = remember(scope) { MlKitModelDownloadSession(scope) { currentDownload(it) } }
    DisposableEffect(session) { onDispose { session.reset() } }
    return session
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MlKitModelDownloadActions(
    running: Boolean,
    downloadLabel: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    requestId: Long,
    downloadEnabled: Boolean = true,
    trailingAction: @Composable () -> Unit = {},
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f, fill = false), enabled = !running && downloadEnabled, onClick = onDownload) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (running) stringResource(R.string.settings_mlkit_model_downloading) else downloadLabel)
            }
            if (running) {
                ModelDownloadCancelButton(requestKey = requestId.toString(), onCancel = onCancel)
            }
        }
        trailingAction()
    }
}

@Composable
internal fun MlKitSourceModelDeleteButton(
    pair: Pair<String, String>,
    sourceLanguageName: String,
    enabled: Boolean,
    onDelete: (Pair<String, String>) -> Unit,
) {
    // Switching language invalidates an open confirmation instead of retargeting its action.
    var showConfirm by remember(pair) { mutableStateOf(false) }
    OutlinedButton(enabled = enabled, onClick = { showConfirm = true }) {
        Text(stringResource(R.string.settings_model_delete_confirm_yes))
    }
    if (showConfirm && enabled) {
        CatalystAlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_model_delete_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sourceLanguageName)
                    Text(stringResource(R.string.settings_model_delete_confirm_message))
                }
            },
            confirmButton = {
                DestructiveTextButton(
                    label = stringResource(R.string.settings_model_delete_confirm_yes),
                    onClick = {
                        if (showConfirm && enabled) {
                            showConfirm = false
                            onDelete(pair)
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.settings_model_delete_confirm_no))
                }
            },
        )
    }
}

/** The timeout advice and browser action are identical at every manual-download entry point. */
@Composable
internal fun MlKitModelDownloadFeedback(state: MlKitModelDownloadState) {
    val message = when (state.phase) {
        MlKitDownloadPhase.TIMED_OUT -> stringResource(R.string.settings_mlkit_download_timeout_help)
        MlKitDownloadPhase.FAILED -> stringResource(R.string.settings_mlkit_model_download_failed, state.error.orEmpty())
        MlKitDownloadPhase.CANCELLED -> stringResource(R.string.settings_mlkit_model_waiting_stopped)
        else -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        state.browserUrl?.let { url ->
            ExternalBrowserLinkButton(
                url = url,
                actionLabel = stringResource(R.string.settings_mlkit_download_open_browser),
            )
        }
    }
}
