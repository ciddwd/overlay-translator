package com.gameocr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.ocr.MlKitOcrDownloadSession
import com.gameocr.app.translate.MlKitDownloadPhase
import com.gameocr.app.translate.MlKitModelDownloadState

@Composable
internal fun rememberMlKitOcrDownloadSession(download: suspend () -> Unit): MlKitOcrDownloadSession {
    val scope = rememberCoroutineScope()
    val currentDownload by rememberUpdatedState(download)
    val session = remember(scope) { MlKitOcrDownloadSession(scope) { currentDownload() } }
    DisposableEffect(session) { onDispose { session.reset() } }
    return session
}

@Composable
internal fun MlKitOcrDownloadFeedback(state: MlKitModelDownloadState, onCancel: () -> Unit) {
    when (state.phase) {
        MlKitDownloadPhase.DOWNLOADING -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_ocr_chip_devanagari) + " " +
                stringResource(R.string.settings_mlkit_model_downloading), modifier = Modifier.weight(1f))
            ModelDownloadCancelButton(requestKey = state.requestId.toString(), onCancel = onCancel)
        }
        MlKitDownloadPhase.READY -> Text(stringResource(R.string.settings_auto_ocr_download_complete))
        else -> MlKitModelDownloadFeedback(state)
    }
}
