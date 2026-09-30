package com.ashishkumar.nivara.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Sage,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = LightSage,
    background = WarmWhite,
    surface = WarmWhite,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = LightSage,
    onPrimary = DarkGreen,
    secondary = Sage,
    background = DarkGreen,
    surface = DarkGreen,
    onSurface = PaleGreen,
)

@Composable
fun NivaraTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
