package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VexDarkColorScheme = darkColorScheme(
    primary = VexAccent,
    onPrimary = VexTextPrimary,
    primaryContainer = VexAccentDark,
    onPrimaryContainer = VexTextPrimary,
    secondary = VexAccentLight,
    onSecondary = VexBackground,
    background = VexBackground,
    onBackground = VexTextPrimary,
    surface = VexPanel,
    onSurface = VexTextPrimary,
    surfaceVariant = VexPanelElevated,
    onSurfaceVariant = VexTextSecondary,
    outline = VexBorder,
    outlineVariant = VexBorder.copy(alpha = 0.5f)
)

@Composable
fun VexEditTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VexDarkColorScheme,
        typography = Typography,
        content = content
    )
}
