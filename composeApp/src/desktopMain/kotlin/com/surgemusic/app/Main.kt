package com.surgemusic.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Compose Desktop entry point — launches the themed window (plan Task 5). */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SurgeMusic",
        state = rememberWindowState(
            width = 1080.dp,
            height = 720.dp,
            // CMP 1.8.1 has no Platform.WindowCenter — WindowPosition(Alignment)
            // is the Aligned variant that centers the window on screen.
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        SurgeMusicTheme {
            // Task 6 replaces this placeholder
            CyberSkeletonPlaceholder()
        }
    }
}

@Composable
private fun CyberSkeletonPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "SURGEMUSIC // DESKTOP SKELETON",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
