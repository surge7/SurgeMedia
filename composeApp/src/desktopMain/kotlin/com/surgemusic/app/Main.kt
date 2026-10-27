package com.surgemusic.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import com.surgemusic.app.ui.login.DesktopLoginScreen
import kotlinx.coroutines.delay

/**
 * Compose Desktop entry point (plan Tasks 5/6): the themed window hosts the real [App]
 * shell over the desktop DI container — same wiring as androidMain MainActivity minus the
 * platform extras (no edge-to-edge, no permission prompts, no foreground service). The
 * login overlay is the paste-cookie [DesktopLoginScreen]; its single `() -> Unit` exit is
 * mapped to the shell's `(Boolean) -> Unit` as `false` — App only hides the overlay on
 * that callback (App.kt passes `{ showLogin = false }`), session state drives the UI.
 *
 * macOS chrome: the native title bar is turned into an overlay (see [MacWindowChrome]) so
 * the app paints the top strip in its own theme colour instead of AppKit painting it with
 * the system appearance. The traffic lights and drag-to-move stay native.
 */
fun main() {
    // AWT reads the application appearance once, while the toolkit starts, so it has to be
    // set before any window exists. "system" makes the native chrome — and the native menu
    // bar — follow the OS appearance instead of being pinned to Aqua.
    System.setProperty("apple.awt.application.appearance", "system")

    // Height of the native title bar the shell has to leave free at the top of the window
    // (that is where the traffic lights live). Measured, not assumed; 0 off macOS, where
    // the platform insets the content itself.
    val titleBarHeight = if (IsMacOs) measureNativeTitleBarHeight() else 0.dp

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Surge Media",
            state = rememberWindowState(
                width = 1080.dp,
                height = 720.dp,
                // CMP 1.8.1 has no Platform.WindowCenter — WindowPosition(Alignment)
                // is the Aligned variant that centers the window on screen.
                position = WindowPosition(Alignment.Center),
            ),
        ) {
            // Native title-bar overlay: transparent bar, full-height content, no native
            // title text. Applied per window instance — the shell creates a fresh frame
            // every time it is un-hidden from the tray.
            NativeTitleBarOverlay()

            val (themeMode, systemDark, background) = rememberResolvedTheme()

            // Record the window facts once the native window has settled, so the geometry
            // and the painted colour can be checked from outside the process.
            LaunchedEffect(window) {
                for (attempt in 0 until 40) {
                    if (isFullWindowContent(window)) break
                    delay(50)
                }
                writeWindowDiagnostic(window, titleBarHeight, themeMode, systemDark, background)
            }

            // App() applies SurgeMediaTheme itself (commonMain App.kt) — wrapping again here
            // would double-apply the theme; androidMain's setContent calls it bare as well.
            App(
                searchViewModel = AppContainer.searchViewModel,
                downloadsViewModel = AppContainer.downloadsViewModel,
                libraryViewModel = AppContainer.libraryViewModel,
                settingsViewModel = AppContainer.settingsViewModel,
                sessionRepository = AppContainer.sessionStore,
                loginOverlay = { onFinished -> DesktopLoginScreen(onFinished = { onFinished(false) }) },
                onPrepareDownload = { row -> AppContainer.prepare(row) },
                onStartDownload = { row, prepared, wanted -> AppContainer.startDownload(row, prepared, wanted) },
                // macOS: reserve the native title-bar strip and paint it in the theme colour.
                windowTopInset = titleBarHeight,
            )
        }
    }
}
