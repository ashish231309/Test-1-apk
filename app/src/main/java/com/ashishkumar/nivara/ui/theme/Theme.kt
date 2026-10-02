package com.ashishkumar.nivara.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = NivaraEvergreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E9DE),
    onPrimaryContainer = NivaraEvergreenDark,
    secondary = NivaraMutedGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E9E3),
    onSecondaryContainer = Color(0xFF24372F),
    tertiary = NivaraGold,
    onTertiary = Color.White,
    tertiaryContainer = NivaraPaleGold,
    onTertiaryContainer = Color(0xFF281A00),
    error = NivaraError,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = NivaraPaper,
    onBackground = NivaraInk,
    surface = Color(0xFFFAFCF9),
    onSurface = NivaraInk,
    surfaceVariant = Color(0xFFE3EAE4),
    onSurfaceVariant = Color(0xFF424F47),
    outline = Color(0xFF727D75),
    outlineVariant = Color(0xFFC4CEC6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F5F1),
    surfaceContainer = Color(0xFFECF1EC),
    surfaceContainerHigh = Color(0xFFE6ECE6),
    surfaceContainerHighest = Color(0xFFE0E7E0),
)

private val DarkColors = darkColorScheme(
    primary = NivaraMint,
    onPrimary = Color(0xFF07372C),
    primaryContainer = Color(0xFF254E42),
    onPrimaryContainer = Color(0xFFD0E9DC),
    secondary = Color(0xFFBACCC0),
    onSecondary = Color(0xFF25352D),
    secondaryContainer = NivaraForestSurface,
    onSecondaryContainer = Color(0xFFD7E5DB),
    tertiary = Color(0xFFE0C88F),
    onTertiary = Color(0xFF3D2E08),
    tertiaryContainer = Color(0xFF54451F),
    onTertiaryContainer = Color(0xFFF1E2BD),
    error = NivaraDarkError,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NivaraNight,
    onBackground = NivaraOnNight,
    surface = NivaraNightSurface,
    onSurface = NivaraOnNight,
    surfaceVariant = Color(0xFF39463F),
    onSurfaceVariant = Color(0xFFC0CCC3),
    outline = Color(0xFF8A978E),
    outlineVariant = Color(0xFF424F47),
    surfaceContainerLowest = Color(0xFF0B120F),
    surfaceContainerLow = Color(0xFF151D19),
    surfaceContainer = Color(0xFF1A241F),
    surfaceContainerHigh = Color(0xFF242F29),
    surfaceContainerHighest = Color(0xFF2F3A34),
)

private val NivaraTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontSize = 45.sp, lineHeight = 52.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.15).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.25.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

private val NivaraShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun NivaraTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        typography = NivaraTypography,
        shapes = NivaraShapes,
        content = content,
    )
}
