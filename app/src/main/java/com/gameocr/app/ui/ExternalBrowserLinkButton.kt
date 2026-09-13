package com.gameocr.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared address-style action for opening a verified external page or model URL. */
@Composable
internal fun ExternalBrowserLinkButton(
    url: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { openExternalBrowser(context, url) },
        modifier = modifier.fillMaxWidth().semantics { contentDescription = actionLabel },
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
        Text(
            text = url,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
