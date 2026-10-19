package com.surgemusic.app.settings

import com.surgemusic.app.ThemeMode
import com.surgemusic.app.themeModeOf
import com.surgemusic.app.ui.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings (plan Task 7): SharedPreferences-backed [StateFlow]s.
 * Defaults: Wi-Fi only = on, mirror = `https://rutracker.org`, 3 concurrent downloads,
 * theme = System. Setters persist first, then emit; writes come from the UI thread.
 * Implements the commonMain [AppSettings] slice so the Settings screen (Task 9) drives it.
 */
class SettingsStore(private val store: KeyValueStore) : AppSettings {
    private val _wifiOnly = MutableStateFlow(store.getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _mirror = MutableStateFlow(store.getString(KEY_MIRROR) ?: DEFAULT_MIRROR)
    override val mirror: StateFlow<String> = _mirror.asStateFlow()

    private val _concurrentDownloads =
        MutableStateFlow(store.getInt(KEY_CONCURRENT_DOWNLOADS, DEFAULT_CONCURRENT_DOWNLOADS))
    override val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    private val _themeMode = MutableStateFlow(themeModeOf(store.getString(KEY_THEME_MODE)))
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    override fun setWifiOnly(enabled: Boolean) {
        store.putBoolean(KEY_WIFI_ONLY, enabled)
        _wifiOnly.value = enabled
    }

    override fun setMirror(url: String) {
        store.putString(KEY_MIRROR, url)
        _mirror.value = url
    }

    /** The Settings screen exposes a 1–5 slider (Task 9); clamping keeps hand-edited values safe. */
    override fun setConcurrentDownloads(count: Int) {
        val clamped = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        store.putInt(KEY_CONCURRENT_DOWNLOADS, clamped)
        _concurrentDownloads.value = clamped
    }

    override fun setThemeMode(mode: ThemeMode) {
        store.putString(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    private companion object {
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
