package com.surgemusic.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Surge Media dual palette. The dark scheme keeps the electric-cyan brand accent on a deep
 * space-navy stage; the light scheme is its daytime twin — same hue family, deepened to stay
 * legible on paper-white surfaces. Kind accents: cyan = audio, violet = books,
 * amber = video/movies (the cinema hue), rose = errors.
 */

// --- Dark scheme -----------------------------------------------------------

val SurgeBackground = Color(0xFF0B0E17)
val SurgeSurface = Color(0xFF131929)
val SurgeSurfaceAlt = Color(0xFF1B2338)
val SurgeText = Color(0xFFE9EEFB)
val SurgeTextDim = Color(0xFF97A3C4)
val SurgeCyan = Color(0xFF00E5FF)
val OnSurgeCyan = Color(0xFF031318)
val SurgeViolet = Color(0xFF8E7BFF)
val OnSurgeViolet = Color(0xFF14102B)
val SurgeAmber = Color(0xFFFFC53D)
val OnSurgeAmber = Color(0xFF231A03)

val SurgeDarkColors = darkColorScheme(
    primary = SurgeCyan,
    onPrimary = OnSurgeCyan,
    primaryContainer = Color(0xFF0B3038),
    onPrimaryContainer = Color(0xFF7FF4FF),
    secondary = SurgeViolet,
    onSecondary = OnSurgeViolet,
    secondaryContainer = Color(0xFF2A2350),
    onSecondaryContainer = Color(0xFFCFC5FF),
    tertiary = SurgeAmber,
    onTertiary = OnSurgeAmber,
    tertiaryContainer = Color(0xFF443505),
    onTertiaryContainer = Color(0xFFFFE08A),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2B0710),
    errorContainer = Color(0xFF4A1120),
    onErrorContainer = Color(0xFFFFD3DC),
    background = SurgeBackground,
    onBackground = SurgeText,
    surface = SurgeSurface,
    onSurface = SurgeText,
    surfaceVariant = SurgeSurfaceAlt,
    onSurfaceVariant = SurgeTextDim,
    outline = Color(0xFF2A3352),
    outlineVariant = Color(0xFF1E2742),
    inverseSurface = SurgeText,
    inverseOnSurface = SurgeSurface,
    inversePrimary = Color(0xFF0087A8),
)

// --- Light scheme ----------------------------------------------------------

val SurgeLightColors = lightColorScheme(
    primary = Color(0xFF0087A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9F3FC),
    onPrimaryContainer = Color(0xFF00495A),
    secondary = Color(0xFF5D4FD6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DFFF),
    onSecondaryContainer = Color(0xFF1E1550),
    tertiary = Color(0xFFA96A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE9B8),
    onTertiaryContainer = Color(0xFF3A2600),
    error = Color(0xFFC0334F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF53001A),
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF12182A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12182A),
    surfaceVariant = Color(0xFFE7EBF6),
    onSurfaceVariant = Color(0xFF55618A),
    outline = Color(0xFFC3CCE4),
    outlineVariant = Color(0xFFDDE3F2),
    inverseSurface = Color(0xFF12182A),
    inverseOnSurface = Color(0xFFF5F7FC),
    inversePrimary = SurgeCyan,
)
