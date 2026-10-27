package com.surgemusic.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

/**
 * macOS window chrome for the Compose Desktop shell.
 *
 * The shell's window is a plain decorated `JFrame` (CMP's `ComposeWindow`), so AppKit paints
 * a native title bar above the Compose content. That title bar is painted with the *system*
 * appearance, while the Compose content follows the app's own theme — so as soon as the app
 * is dark and the title bar is light, the top of the window shows a light band.
 *
 * The platform-native fix is a native title-bar overlay, not a painted rectangle: the three
 * `apple.awt.*` client properties below make the native title bar transparent and let the
 * content view run the full height of the window. AppKit keeps owning the title bar, so the
 * traffic lights and drag-to-move stay native; the strip that the title bar used to occupy
 * is then drawn by the app in its own theme colour.
 *
 * Note on `apple.awt.application.appearance`: the bundled JDK 21.0.1 does not implement it.
 * Its macOS backend only knows `fullWindowContent`, `transparentTitleBar`,
 * `windowTitleVisible`, `draggableWindowBackground`, `documentModalSheet`,
 * `fullscreenable`, `brushMetalLook`, `enableTemplateImages`, `fileDialogForDirectories`,
 * `windowShadow.revalidateNow` and `dnd.defaultDragImageSize` — there is no appearance
 * handling anywhere in `java.desktop`. The property is still set at startup so the native
 * chrome follows the OS on JDKs that do implement it, but the transparent title bar is what
 * actually drives this fix.
 */

/** True on macOS; the client properties below are macOS-only and are no-ops elsewhere. */
internal val IsMacOs: Boolean = System.getProperty("os.name").startsWith("Mac")

/** Native title-bar height used when it cannot be measured (headless / no display). */
private val FallbackTitleBarHeight: Dp = 28.dp

/** Path of the window diagnostic file, next to the other persisted app state. */
private val DiagnosticFile: Path =
    Path.of(System.getProperty("user.home"), ".surgemusic", "window-diag.log")

/**
 * Height of the native macOS title bar in dp, measured from a throwaway decorated frame.
 *
 * AWT reports window insets in user space, which on macOS is exactly the unit Compose's
 * `dp` maps to (1 dp = 1 pt = 1/72"), so the number needs no conversion. A plain `JFrame`
 * gets the standard title bar (28 pt on current macOS); a window with a unified toolbar
 * would be taller, so the height is measured rather than assumed.
 */
internal fun measureNativeTitleBarHeight(): Dp {
    val measured = if (SwingUtilities.isEventDispatchThread()) {
        probeTitleBarHeight()
    } else {
        var value = 0
        SwingUtilities.invokeAndWait { value = probeTitleBarHeight() }
        value
    }
    return if (measured > 0) measured.dp else FallbackTitleBarHeight
}

/**
 * Realise a 1x1 frame off-screen and read its title-bar inset. The frame is disposed
 * immediately, so nothing is ever shown.
 */
private fun probeTitleBarHeight(): Int {
    val probe = JFrame()
    return try {
        probe.setSize(1, 1)
        probe.setLocation(-10_000, -10_000)
        probe.isVisible = true
        probe.insets.top
    } finally {
        probe.dispose()
    }
}

/**
 * Put the window into native full-window-content mode.
 *
 * - `apple.awt.fullWindowContent` — the content view fills the whole window, including the
 *   area under the title bar;
 * - `apple.awt.transparentTitleBar` — AppKit stops painting the title bar background;
 * - `apple.awt.windowTitleVisible` — the native title text is hidden (the app draws its own
 *   header strip, and the native text would be drawn in the *system* colours on top of it).
 *
 * The JDK applies these live through `CPlatformWindow`'s `ClientPropertyApplicator`, so the
 * call works both before the window is shown (the first paint is already full height) and
 * after it is shown.
 */
internal fun applyFullWindowContent(frame: JFrame) {
    val rootPane = frame.rootPane
    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
}

/**
 * True when the window's content really starts at the top of the frame (no native title
 * bar inset). This is the programmatic check that the overlay took effect: the offset must
 * be 0, not 28.
 */
internal fun isFullWindowContent(frame: JFrame): Boolean {
    if (!frame.isShowing) return false
    val offset = runCatching {
        frame.contentPane.locationOnScreen.y - frame.locationOnScreen.y
    }.getOrElse { return false }
    return offset == 0
}

/**
 * Switch this window to the native full-window-content overlay and keep it there.
 *
 * The client properties are applied exactly once per window instance — re-applying them on
 * every recomposition would re-trigger the native update for nothing. A second pass runs
 * after the window is up in case the first one landed before the native peer existed, so the
 * window ends up in the right state regardless of the order in which the platform creates it.
 */
@Composable
internal fun FrameWindowScope.NativeTitleBarOverlay() {
    if (!IsMacOs) return

    val applied = remember { AtomicBoolean(false) }
    if (applied.compareAndSet(false, true)) {
        applyFullWindowContent(window)
    }

    LaunchedEffect(window) {
        repeat(40) {
            if (isFullWindowContent(window)) return@LaunchedEffect
            applyFullWindowContent(window)
            delay(50)
        }
    }
}

/**
 * Record the window facts in `~/.surgemusic/window-diag.log` (one file, overwritten on every
 * launch). The packaged app is started from Finder, so its stdout is not reachable from a
 * terminal — this file is how the geometry and the resolved theme are checked from outside
 * the process.
 */
internal fun writeWindowDiagnostic(
    frame: JFrame,
    titleBarHeight: Dp,
    themeMode: ThemeMode,
    systemDark: Boolean,
    background: Color,
) {
    val contentTopOffset = runCatching {
        frame.contentPane.locationOnScreen.y - frame.locationOnScreen.y
    }.getOrDefault(-1)
    val text = buildString {
        appendLine("time=${Instant.now()}")
        appendLine("pid=${ProcessHandle.current().pid()}")
        appendLine("isMacOs=$IsMacOs")
        appendLine("nativeTitleBarHeightDp=${titleBarHeight.value}")
        appendLine("contentPaneTopOffsetPx=$contentTopOffset")
        appendLine("fullWindowContent=${contentTopOffset == 0}")
        appendLine("frameInsets=${frame.insets}")
        appendLine("themeMode=$themeMode")
        appendLine("isSystemInDarkTheme=$systemDark")
        appendLine("resolvedBackground=#%08X".format(background.toArgb()))
    }
    runCatching {
        Files.createDirectories(DiagnosticFile.parent)
        Files.writeString(DiagnosticFile, text)
    }
}

/**
 * Snapshot of the theme the shell is currently painting with, for the diagnostic file.
 * Mirrors `SurgeMediaTheme`'s own resolution so the logged colour is the one on screen.
 */
@Composable
internal fun rememberResolvedTheme(): Triple<ThemeMode, Boolean, Color> {
    val systemDark = isSystemInDarkTheme()
    val themeMode = AppContainer.settings.themeMode.value
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val background = if (dark) {
        com.surgemusic.app.ui.theme.SurgeDarkColors.background
    } else {
        com.surgemusic.app.ui.theme.SurgeLightColors.background
    }
    return Triple(themeMode, systemDark, background)
}
