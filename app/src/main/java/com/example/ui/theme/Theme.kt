package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MonochromeColorScheme = darkColorScheme(
    primary = White,
    onPrimary = Black,
    secondary = MutedGray,
    onSecondary = White,
    background = Black,
    onBackground = White,
    surface = DarkGray,
    onSurface = White,
    outline = OutlinedGray,
    outlineVariant = MediumGray
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        typography = Typography,
        content = content
    )
}
