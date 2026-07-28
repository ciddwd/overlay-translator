package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gameocr.app.overlay.FloatingMenuTourPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalystAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    contentScrollable: Boolean = true,
) {
    val baseColors = MaterialTheme.colorScheme
    val palette = FloatingMenuTourPalette.colors(
        nightMode = baseColors.background.luminance() < 0.5f,
    )
    val surfaceColor = Color(palette.surface)
    val textColor = Color(palette.text)
    val secondaryTextColor = Color(palette.secondaryText)
    val accentColor = Color(palette.accent)
    val actionTextColor = Color(palette.actionText)
    val borderColor = Color(palette.border)
    val dialogColors = baseColors.copy(
        primary = accentColor,
        onPrimary = actionTextColor,
        background = surfaceColor,
        surface = surfaceColor,
        onBackground = textColor,
        onSurface = textColor,
        onSurfaceVariant = secondaryTextColor,
        outline = borderColor,
        outlineVariant = borderColor,
    )
    val maxHeight = catalystDialogMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        MaterialTheme(colorScheme = dialogColors) {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight)
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(8.dp),
                color = surfaceColor,
                contentColor = textColor,
                border = BorderStroke(1.dp, borderColor),
                shadowElevation = 8.dp,
            ) {
                Column {
                    icon?.let { iconContent ->
                        CompositionLocalProvider(LocalContentColor provides accentColor) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, top = 24.dp, end = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                iconContent()
                            }
                        }
                    }
                    title?.let { titleContent ->
                        CompositionLocalProvider(LocalContentColor provides textColor) {
                            ProvideTextStyle(
                                MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 24.dp,
                                            top = if (icon == null) 24.dp else 16.dp,
                                            end = 24.dp,
                                            bottom = 16.dp,
                                        ),
                                ) {
                                    titleContent()
                                }
                            }
                        }
                    }
                    text?.let { textContent ->
                        CompositionLocalProvider(LocalContentColor provides secondaryTextColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                val contentModifier = Modifier
                                    .weight(1f, fill = false)
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .let { baseModifier ->
                                        if (contentScrollable) {
                                            baseModifier.verticalScroll(rememberScrollState())
                                        } else {
                                            baseModifier
                                        }
                                    }
                                Box(modifier = contentModifier) {
                                    textContent()
                                }
                            }
                        }
                    }
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

private const val CATALYST_DIALOG_MAX_HEIGHT_DP = 640f
private const val CATALYST_DIALOG_MAX_SCREEN_HEIGHT_FRACTION = 0.85f

internal fun catalystDialogMaxHeightDp(screenHeightDp: Int): Float =
    minOf(
        CATALYST_DIALOG_MAX_HEIGHT_DP,
        screenHeightDp * CATALYST_DIALOG_MAX_SCREEN_HEIGHT_FRACTION,
    )
