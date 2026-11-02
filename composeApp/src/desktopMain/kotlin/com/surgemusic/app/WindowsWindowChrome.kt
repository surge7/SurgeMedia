package com.surgemusic.app

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/**
 * Windows window chrome for the Compose Desktop shell — the platform-native twin of
 * [MacWindowChrome]'s title-bar overlay.
 *
 * On macOS the shell turns the native title bar transparent and paints the strip in its
 * own theme colour. Windows cannot hand the caption area to the app, but Windows 11 can
 * paint it in an arbitrary colour: `DwmSetWindowAttribute(DWMWA_CAPTION_COLOR)` sets the
 * caption background, `DWMWA_TEXT_COLOR` the caption text. This keeps the exact design
 * parity — dark theme gets the dark caption, light theme the light one — instead of the
 * light caption Windows would otherwise pin on every JVM window.
 *
 * On Windows 10 (no caption-colour attribute) the call fails and the code falls back to
 * `DWMWA_USE_IMMERSIVE_DARK_MODE`, which renders the standard dark caption. On anything
 * older nothing happens and the default chrome stays — cosmetic only, functionality is
 * unaffected. JNA classes load lazily and are touched only on [IsWindows], so the macOS
 * build never pulls them.
 */
internal object WindowsWindowChrome {

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    /** Null off Windows or if dwmapi.dll is unavailable (best-effort chrome, never fatal). */
    private val dwmApi: DwmApi? by lazy {
        if (!IsWindows) {
            null
        } else {
            runCatching { Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }
    }

    /** Applies the theme-coloured caption; silently does nothing off Windows or on failure. */
    fun apply(window: Window, captionArgb: Int, textArgb: Int, dark: Boolean) {
        val api = dwmApi ?: return
        runCatching {
            val hwnd = Pointer(Native.getComponentID(window).toLong() and 0xFFFFFFFFL)
            val caption = Memory(4).apply { setInt(0, argbToColorref(captionArgb)) }
            val captionAccepted = api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, caption, 4) == 0
            if (!captionAccepted) {
                // Pre-22000 Windows: only the boolean dark-mode attribute exists.
                Memory(4).apply { setInt(0, if (dark) 1 else 0) }.let {
                    api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, it, 4)
                }
            }
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, Memory(4).apply { setInt(0, argbToColorref(textArgb)) }, 4)
        }
    }

    /** COLORREF (0x00BBGGRR) from a Compose/ARGB int — channels are stored reversed. */
    private fun argbToColorref(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
