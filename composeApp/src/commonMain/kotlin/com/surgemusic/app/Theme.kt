package com.surgemusic.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.surgemusic.app.ui.theme.CyberDarkColors
import com.surgemusic.app.ui.theme.CyberShapes
import com.surgemusic.app.ui.theme.CyberTypography

/**
 * CyberDeck design system — dark-only cyberpunk theme.
 *
 * [darkTheme] is kept for source compatibility but its value is ignored: the design
 * always applies the dark cyber scheme.
 */
@Composable
fun SurgeMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CyberDarkColors,
        typography = CyberTypography,
        shapes = CyberShapes,
        content = content,
    )
}
