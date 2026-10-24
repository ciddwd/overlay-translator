package com.gameocr.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gameocr.app.R
import com.gameocr.app.download.ModelDownloadProgressText

/**
 * The one download progress display shared by settings and model management: a bar, then
 * "550 / 1310 MB  42%" on the left and the current speed on the right.
 */
@Composable
internal fun ModelDownloadProgressLine(
    downloaded: Long,
    total: Long,
    bytesPerSecond: Long,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val percent = ModelDownloadProgressText.percent(downloaded, total)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fraction = ModelDownloadProgressText.barFraction(downloaded, total, active)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row {
            Text(
                modelDownloadAmountText(downloaded, total),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active && bytesPerSecond > 0) {
                Text(
                    stringResource(R.string.model_download_speed, Formatter.formatShortFileSize(context, bytesPerSecond)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "550 / 1310 MB  42%", or "550 MB" while the total size is unknown. */
@Composable
internal fun modelDownloadAmountText(downloaded: Long, total: Long): String {
    val percent = ModelDownloadProgressText.percent(downloaded, total)
    val done = ModelDownloadProgressText.megabytes(downloaded)
    return if (percent == null) stringResource(R.string.model_download_amount, done)
    else stringResource(R.string.model_download_amount_total, done, ModelDownloadProgressText.megabytes(total), percent)
}
