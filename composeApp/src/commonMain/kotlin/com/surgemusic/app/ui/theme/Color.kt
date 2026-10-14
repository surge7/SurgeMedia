package com.surgemusic.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val CyberBackground = Color(0xFF0B0E17)
val CyberSurface = Color(0xFF131729)
val CyberSurfaceAlt = Color(0xFF1A2038)
val CyberText = Color(0xFFE8F4FD)
val CyberTextDim = Color(0xFF93A0C2)
val CyberCyan = Color(0xFF00E5FF)
val OnCyberCyan = Color(0xFF04121A)
val CyberMagenta = Color(0xFFFF3D81)
val OnCyberMagenta = Color(0xFF1B0712)
val CyberYellow = Color(0xFFF5D90A)
val OnCyberYellow = Color(0xFF141003)
val CyberGrid = Color(0xFF232A4D)
val CyberGridDim = Color(0xFF1A2038)

val CyberDarkColors = darkColorScheme(
    primary = CyberCyan,
    onPrimary = OnCyberCyan,
    primaryContainer = Color(0xFF0A2A33),
    onPrimaryContainer = Color(0xFF7FF4FF),
    secondary = CyberMagenta,
    onSecondary = OnCyberMagenta,
    secondaryContainer = Color(0xFF381422),
    onSecondaryContainer = Color(0xFFFF9EC4),
    tertiary = CyberYellow,
    onTertiary = OnCyberYellow,
    error = CyberMagenta,
    onError = OnCyberMagenta,
    background = CyberBackground,
    onBackground = CyberText,
    surface = CyberSurface,
    onSurface = CyberText,
    surfaceVariant = CyberSurfaceAlt,
    onSurfaceVariant = CyberTextDim,
    outline = CyberGrid,
    outlineVariant = CyberGridDim,
)
