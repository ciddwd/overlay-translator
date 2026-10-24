package com.gameocr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// shadcn/UI Slate palette —— 冷调深蓝灰，深色 surface 偏深蓝（不是纯中性灰）。
val Slate50 = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)
val Slate950 = Color(0xFF020617)

/**
 * Slate 冷蓝灰主题。覆盖所有 Material3 surface 体系，避免默认 surfaceTint=primary
 * 导致 Card / Surface 染上紫调。
 */
internal val SlateLight = lightColorScheme(
    primary = Slate900,
    onPrimary = Slate50,
    primaryContainer = Slate200,
    onPrimaryContainer = Slate900,

    secondary = Slate700,
    onSecondary = Slate50,
    secondaryContainer = Slate200,
    onSecondaryContainer = Slate800,

    tertiary = Slate600,
    onTertiary = Slate50,
    tertiaryContainer = Slate100,
    onTertiaryContainer = Slate800,

    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,

    // 完整 surface tones —— 消除紫调
    surfaceTint = Slate500,
    surfaceBright = Color.White,
    surfaceDim = Slate200,
    surfaceContainer = Slate100,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Slate50,
    surfaceContainerHigh = Slate200,
    surfaceContainerHighest = Slate300,

    inverseSurface = Slate900,
    inverseOnSurface = Slate50,
    inversePrimary = Slate300,

    outline = Slate300,
    outlineVariant = Slate200,
    scrim = Color(0x66000000),

    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

internal val SlateDark = darkColorScheme(
    primary = Slate50,
    onPrimary = Slate900,
    primaryContainer = Slate800,
    onPrimaryContainer = Slate50,

    secondary = Slate300,
    onSecondary = Slate900,
    secondaryContainer = Slate800,
    onSecondaryContainer = Slate200,

    tertiary = Slate400,
    onTertiary = Slate900,
    tertiaryContainer = Slate800,
    onTertiaryContainer = Slate100,

    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,

    surfaceTint = Slate500,
    surfaceBright = Slate800,
    surfaceDim = Slate950,
    surfaceContainer = Slate900,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Slate700,

    inverseSurface = Slate100,
    inverseOnSurface = Slate900,
    inversePrimary = Slate700,

    outline = Slate700,
    outlineVariant = Slate800,
    scrim = Color(0x99000000),

    error = Color(0xFFEF4444),
    onError = Slate950,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
)

/** 主题模式：值与 [com.gameocr.app.data.ThemeModePrefs] 中的常量对应。 */
object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

@Composable
fun GameOcrTheme(
    themeMode: Int = ThemeMode.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) SlateDark else SlateLight,
        content = content
    )
}
