package com.gameocr.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gameocr.app.R

/** Shared with the existing manga model header so typography and spacing stay identical. */
@Composable
internal fun ModelVersionRow(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_paddle_model_version_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp))
    }
}
