package com.surgemusic.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.surgemusic.app.ui.login.DesktopLoginScreen

/**
 * Compose Desktop entry point (plan Tasks 5/6): the themed window hosts the real [App]
 * shell over the desktop DI container — same wiring as androidMain MainActivity minus the
 * platform extras (no edge-to-edge, no permission prompts, no foreground service). The
 * login overlay is the paste-cookie [DesktopLoginScreen]; its single `() -> Unit` exit is
 * mapped to the shell's `(Boolean) -> Unit` as `false` — App only hides the overlay on
 * that callback (App.kt passes `{ showLogin = false }`), session state drives the UI.
 */
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
        // App() applies SurgeMusicTheme itself (commonMain App.kt) — wrapping again here
        // would double-apply the theme; androidMain's setContent calls it bare as well.
        App(
            searchViewModel = AppContainer.searchViewModel,
            downloadsViewModel = AppContainer.downloadsViewModel,
            settingsViewModel = AppContainer.settingsViewModel,
            sessionRepository = AppContainer.sessionStore,
            loginOverlay = { onFinished -> DesktopLoginScreen(onFinished = { onFinished(false) }) },
            onDownload = { row -> AppContainer.download(row) },
        )
    }
}
