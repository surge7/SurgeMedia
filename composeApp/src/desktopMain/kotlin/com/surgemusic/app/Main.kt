package com.surgemusic.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import com.surgemusic.app.ui.login.DesktopLoginScreen
import kotlinx.coroutines.delay
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

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
 *
 * Windows chrome: the caption cannot be handed to the app, so [WindowsWindowChrome] paints
 * it in the theme colours via DWM (dark-mode flag on Windows 10) — same visual contract,
 * platform-native mechanics. The title-bar height stays 0: Windows insets the content
 * itself, like every non-macOS platform.
 *
 * Startup hardening: a second launch exits early (process-lifetime FileLock on
 * `~/.surgemusic/app.lock`, see [acquireSingleInstanceLock]) and uncaught exceptions on
 * any thread are appended to `~/.surgemusic/crash.log` (see [installCrashLog]).
 */
fun main() {
    // One instance at a time, before any window/AWT exists. A second launch prints a
    // line and exits; the lock needs no stale-file cleanup (the OS releases it on
    // crash/kill) because it lives for the whole process lifetime.
    if (!acquireSingleInstanceLock()) return

    // Crash log first, so even a startup crash leaves a timestamped trace on disk.
    installCrashLog()

    // OS-language watch: polls every 10s so AppLanguage.SYSTEM follows an OS-language
    // change live (Locale.setDefault + a tick bump → the shell re-resolves its strings)
    // instead of needing an app restart. Daemon loop on Dispatchers.IO, stopped from a
    // shutdown hook so no exit path leaks it. Linux: the environment is fixed for the
    // process lifetime — only the JVM-default watch applies; a full OS-language change
    // still needs an app restart there (see DesktopLocaleMonitor).
    AppContainer.startLocaleMonitor()
    Runtime.getRuntime().addShutdownHook(Thread { AppContainer.stopLocaleMonitor() })

    // AWT reads the application appearance once, while the toolkit starts, so it has to be
    // set before any window exists. "system" makes the native chrome — and the native menu
    // bar — follow the OS appearance instead of being pinned to Aqua. (macOS-only property.)
    if (IsMacOs) System.setProperty("apple.awt.application.appearance", "system")

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
                // CMP 1.12.0 has no Platform.WindowCenter — WindowPosition(Alignment)
                // is the Aligned variant that centers the window on screen.
                position = WindowPosition(Alignment.Center),
            ),
        ) {
            // Native title-bar overlay: transparent bar, full-height content, no native
            // title text. Applied per window instance — the shell creates a fresh frame
            // every time it is un-hidden from the tray.
            NativeTitleBarOverlay()

            val theme = rememberResolvedTheme()

            // Record the window facts once the native window has settled, so the geometry
            // and the painted colour can be checked from outside the process.
            LaunchedEffect(window) {
                for (attempt in 0 until 40) {
                    if (isFullWindowContent(window)) break
                    delay(50)
                }
                writeWindowDiagnostic(window, titleBarHeight, theme.themeMode, theme.systemDark, theme.background)
            }

            // Windows caption in the app's own theme colours — the Windows 11 twin of the
            // macOS title-bar overlay above. Re-applied when the theme changes; the DWM
            // calls are best-effort (no-op off Windows, on Windows 10 only the dark flag).
            if (IsWindows) {
                LaunchedEffect(window, theme.themeMode, theme.systemDark) {
                    while (!window.isShowing) delay(50)
                    WindowsWindowChrome.apply(
                        window,
                        captionArgb = theme.background.toArgb(),
                        textArgb = theme.onSurface.toArgb(),
                        dark = theme.dark,
                    )
                }
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

/**
 * Single instance: `tryLock` on `~/.surgemusic/app.lock` — non-blocking, so a second
 * launch fails fast with a printed line. The channel and lock are pinned in
 * [InstanceLock] for the process lifetime (an unreferenced FileLock can be finalized
 * away, which would silently re-allow a second instance). Returns false when another
 * instance holds the lock; true when this instance owns it — or when the lock file
 * cannot be created at all (an infra failure must not make the app unlaunchable).
 */
private fun acquireSingleInstanceLock(): Boolean {
    val lockFile = Path.of(System.getProperty("user.home"), APP_STATE_DIR, "app.lock")
    val channel = runCatching {
        Files.createDirectories(lockFile.parent)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    }.getOrNull() ?: return true
    val lock = runCatching { channel.tryLock() }.getOrNull()
    if (lock == null) {
        runCatching { channel.close() }
        println("Surge Media is already running — closing this launch.")
        return false
    }
    InstanceLock.channel = channel
    InstanceLock.lock = lock
    return true
}

/** Pins the single-instance FileLock so neither GC nor finalization can release it mid-run. */
private object InstanceLock {
    var channel: FileChannel? = null
    var lock: FileLock? = null
}

/**
 * Crash log: appends one timestamped entry per uncaught exception — thread, exception
 * chain and stack — to `~/.surgemusic/crash.log`, then defers to the previous default
 * handler so console behaviour (stderr stack trace, exit code) is unchanged. The write
 * is best-effort: a logging failure must never mask the original crash.
 */
private fun installCrashLog() {
    val logFile = Path.of(System.getProperty("user.home"), APP_STATE_DIR, "crash.log")
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            Files.createDirectories(logFile.parent)
            Files.writeString(
                logFile,
                buildString {
                    appendLine("${Instant.now()} [${thread.name}] $throwable")
                    var cause: Throwable? = throwable
                    while (cause != null) {
                        for (frame in cause.stackTrace) appendLine("    at $frame")
                        cause = cause.cause
                        if (cause != null) appendLine("Caused by: $cause")
                    }
                    appendLine()
                },
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
        previous?.uncaughtException(thread, throwable)
    }
}

/** State directory under the user home (the session/settings stores live here too). */
private const val APP_STATE_DIR = ".surgemusic"
