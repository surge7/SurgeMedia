package com.surgemusic.app.settings

import com.surgemusic.app.ThemeMode
import com.surgemusic.app.themeModeOf
import com.surgemusic.app.ui.settings.AppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App settings on desktop (plan Task 6): `java.util.Properties` persisted at
 * `~/.surgemusic/settings.properties`. Same defaults as androidMain's `SettingsStore` —
 * Wi-Fi only = on, mirror = `https://rutracker.org`, 3 concurrent downloads, theme =
 * System — and the same keys, so a hand-copied file ports between platforms.
 *
 * Write discipline (desktop twin of androidMain's SharedPreferences `apply()`, which never
 * touches disk on the calling thread): setters mutate the in-memory [Properties] and emit
 * synchronously, then flush a snapshot FIFO on the single-threaded daemon
 * [persistScope] — last write wins on disk, and no file IO runs on the UI (AWT EDT)
 * thread. A failed flush is swallowed like `apply()`; a missing or corrupt file loads as
 * defaults.
 */
class DesktopSettingsStore(
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
) : AppSettings {
    private val propsFile = homeDir.resolve(DIR).resolve(FILE)
    private val ioLock = Any()

    /** All disk writes on one daemon thread: flush order matches setter order. */
    private val persistScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r -> Thread(r, "surgemusic-settings-persist").apply { isDaemon = true } }
            .asCoroutineDispatcher(),
    )

    private val props: Properties = load()

    private val _wifiOnly = MutableStateFlow(readWifiOnly())
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _mirror = MutableStateFlow(readMirror())
    override val mirror: StateFlow<String> = _mirror.asStateFlow()

    private val _concurrentDownloads = MutableStateFlow(readConcurrentDownloads())
    override val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    private val _themeMode = MutableStateFlow(themeModeOf(props.getProperty(KEY_THEME_MODE)))
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    override fun setWifiOnly(enabled: Boolean) {
        val snapshot = synchronized(ioLock) {
            props.setProperty(KEY_WIFI_ONLY, enabled.toString())
            _wifiOnly.value = enabled
            props.clone() as Properties
        }
        flushAsync(snapshot)
    }

    override fun setMirror(url: String) {
        val snapshot = synchronized(ioLock) {
            props.setProperty(KEY_MIRROR, url)
            _mirror.value = url
            props.clone() as Properties
        }
        flushAsync(snapshot)
    }

    /** The Settings screen exposes a 1–5 slider (Task 9); clamping keeps hand-edited values safe. */
    override fun setConcurrentDownloads(count: Int) {
        val clamped = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        val snapshot = synchronized(ioLock) {
            props.setProperty(KEY_CONCURRENT_DOWNLOADS, clamped.toString())
            _concurrentDownloads.value = clamped
            props.clone() as Properties
        }
        flushAsync(snapshot)
    }

    override fun setThemeMode(mode: ThemeMode) {
        val snapshot = synchronized(ioLock) {
            props.setProperty(KEY_THEME_MODE, mode.name)
            _themeMode.value = mode
            props.clone() as Properties
        }
        flushAsync(snapshot)
    }

    private fun load(): Properties {
        val props = Properties()
        runCatching {
            Files.newInputStream(propsFile).use { props.load(it) }
        } // missing or unreadable file → all defaults
        return props
    }

    private fun flushAsync(snapshot: Properties) {
        persistScope.launch {
            runCatching {
                Files.createDirectories(propsFile.parent)
                Files.newOutputStream(propsFile).use { snapshot.store(it, null) }
            } // failed flush is swallowed, like SharedPreferences.apply()
        }
    }

    private fun readWifiOnly(): Boolean =
        props.getProperty(KEY_WIFI_ONLY)?.toBooleanStrictOrNull() ?: DEFAULT_WIFI_ONLY

    private fun readMirror(): String = props.getProperty(KEY_MIRROR) ?: DEFAULT_MIRROR

    private fun readConcurrentDownloads(): Int =
        props.getProperty(KEY_CONCURRENT_DOWNLOADS)?.toIntOrNull() ?: DEFAULT_CONCURRENT_DOWNLOADS

    private companion object {
        const val DIR = ".surgemusic"
        const val FILE = "settings.properties"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_MIRROR = "mirror"
        const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads"
        const val KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_WIFI_ONLY = true
        const val DEFAULT_MIRROR = "https://rutracker.org"
        const val DEFAULT_CONCURRENT_DOWNLOADS = 3
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 5
    }
}
