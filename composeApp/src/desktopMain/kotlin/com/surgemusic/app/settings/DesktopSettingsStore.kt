package com.surgemusic.app.settings

import com.surgemusic.app.ui.settings.AppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings on desktop (plan Task 6): `java.util.Properties` persisted at
 * `~/.surgemusic/settings.properties`. Same defaults as androidMain's `SettingsStore` —
 * Wi-Fi only = on, mirror = `https://rutracker.org`, 3 concurrent downloads — and the same
 * keys, so a hand-copied file ports between platforms.
 *
 * Thread safety: setters take [ioLock] so persist-then-emit is atomic per write (racing
 * setters would otherwise interleave `Properties.store`); the [StateFlow]s themselves are
 * concurrent-safe. In practice writes come from the Compose main thread. Setters persist
 * first, then emit — same order as androidMain. A missing or corrupt file loads as defaults.
 */
class DesktopSettingsStore(
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
) : AppSettings {
    private val propsFile = homeDir.resolve(DIR).resolve(FILE)
    private val ioLock = Any()

    private val props: Properties = load()

    private val _wifiOnly = MutableStateFlow(readWifiOnly())
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _mirror = MutableStateFlow(readMirror())
    override val mirror: StateFlow<String> = _mirror.asStateFlow()

    private val _concurrentDownloads = MutableStateFlow(readConcurrentDownloads())
    override val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    override fun setWifiOnly(enabled: Boolean) {
        synchronized(ioLock) {
            props.setProperty(KEY_WIFI_ONLY, enabled.toString())
            persist()
            _wifiOnly.value = enabled
        }
    }

    override fun setMirror(url: String) {
        synchronized(ioLock) {
            props.setProperty(KEY_MIRROR, url)
            persist()
            _mirror.value = url
        }
    }

    /** The Settings screen exposes a 1–5 slider (Task 9); clamping keeps hand-edited values safe. */
    override fun setConcurrentDownloads(count: Int) {
        val clamped = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        synchronized(ioLock) {
            props.setProperty(KEY_CONCURRENT_DOWNLOADS, clamped.toString())
            persist()
            _concurrentDownloads.value = clamped
        }
    }

    private fun load(): Properties {
        val props = Properties()
        runCatching {
            Files.newInputStream(propsFile).use { props.load(it) }
        } // missing or unreadable file → all defaults
        return props
    }

    private fun persist() {
        Files.createDirectories(propsFile.parent)
        Files.newOutputStream(propsFile).use { props.store(it, null) }
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
        const val DEFAULT_WIFI_ONLY = true
        const val DEFAULT_MIRROR = "https://rutracker.org"
        const val DEFAULT_CONCURRENT_DOWNLOADS = 3
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 5
    }
}
