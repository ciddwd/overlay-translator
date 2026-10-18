package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameocr.app.R
import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrSettings
import com.gameocr.app.data.Language
import java.util.Locale

internal fun autoOcrLanguageGlyph(code: String): String = when (val key = AutoOcrSettings.languageKey(code)) {
    "zh" -> "中"
    "ja" -> "あ"
    "ko" -> "한"
    "en" -> "A"
    "fr" -> "É"
    "ar", "fa", "ur" -> "ع"
    "ru", "be", "uk" -> "Я"
    "hi", "mr", "ne", "sa" -> "अ"
    "he" -> "א"
    "th" -> "ก"
    else -> key.take(2).uppercase(Locale.ROOT)
}

@Composable
internal fun AutoOcrLanguageTile(
    language: Language,
    route: AutoOcrRoute?,
    removable: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val secondary = colors.onSurfaceVariant
    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            // A minimum, rather than a fixed height, lets long labels and large fonts grow.
            Column(
                Modifier.heightIn(min = 150.dp).padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    autoOcrLanguageGlyph(language.code),
                    modifier = Modifier.fillMaxWidth().padding(end = if (removable) 24.dp else 0.dp)
                        .clearAndSetSemantics {},
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(if (AutoOcrSettings.languageKey(language.code) == "zh")
                            R.string.settings_ocr_chip_chinese else language.nameRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(autoOcrRouteLabel(route), style = MaterialTheme.typography.bodySmall,
                        color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (downloading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = colors.primary,
                            trackColor = colors.onSurface.copy(alpha = .2f),
                        )
                    }
                }
            }
        }
        if (removable) {
            // Keep deletion outside the card click target; it only opens the existing confirmation.
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Close, stringResource(R.string.settings_paddle_btn_delete),
                    modifier = Modifier.size(18.dp), tint = secondary)
            }
        }
    }
}

/** Uses the same dashed add-tile treatment as the gallery image grid. */
@Composable
internal fun AutoOcrAddLanguageTile(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val borderColor = colors.outline
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.background,
    ) {
        Column(
            modifier = Modifier.heightIn(min = 150.dp).drawBehind {
                val strokeWidth = 1.dp.toPx()
                val inset = strokeWidth / 2f
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(44.dp).background(colors.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp),
                    tint = colors.onPrimaryContainer)
            }
            Text(stringResource(R.string.settings_auto_ocr_add_language),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}
