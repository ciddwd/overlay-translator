package com.gameocr.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.gameocr.app.R
import com.gameocr.app.download.ModelDownloadCancelConfirmation

@Composable
internal fun ModelDownloadCancelButton(requestKey: String, onCancel: () -> Unit) {
    val confirmation = remember(requestKey) { ModelDownloadCancelConfirmation() }
    val pending by confirmation.request.collectAsState()
    TextButton(onClick = { confirmation.show(requestKey) }) {
        Text(stringResource(R.string.model_download_cancel))
    }
    if (pending == requestKey) {
        ModelDownloadCancelDialog(
            onDismiss = confirmation::dismiss,
            onConfirm = { if (confirmation.consume(requestKey)) onCancel() },
        )
    }
}

@Composable
internal fun ModelDownloadCancelDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    CatalystAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_download_cancel_confirm_title)) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_download_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.model_download_cancel_confirm_ok)) }
        },
    )
}
