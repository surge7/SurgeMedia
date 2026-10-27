package com.surgemusic.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.surgemusic.app.ui.theme.SurgeDarkColors
import com.surgemusic.app.ui.theme.SurgeLightColors
import com.surgemusic.app.ui.theme.SurgeShapes
import com.surgemusic.app.ui.theme.SurgeTypography

/**
 * Theme preference (Settings ▸ Appearance): follow the OS, or force the dark / light
 * Surge Media scheme. Persisted as `theme_mode` by both platform settings stores.
 */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Parse a persisted [ThemeMode] name; anything unknown (or null) falls back to [ThemeMode.SYSTEM]. */
fun themeModeOf(raw: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM

/** True when the dark scheme should be applied for [mode] under the current system setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Surge Media design system — dual-scheme "Surge" theme (deep-navy dark / paper-white
 * light) with rounded shapes and a compact type scale.
 */
@Composable
fun SurgeMediaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (themeMode.isDark()) SurgeDarkColors else SurgeLightColors,
        typography = SurgeTypography,
        shapes = SurgeShapes,
        content = content,
    )
}
