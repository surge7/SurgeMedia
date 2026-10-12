package com.surgemusic.app.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings (plan Task 7): SharedPreferences-backed [StateFlow]s.
 * Defaults: Wi-Fi only = on, mirror = `https://rutracker.org`, 3 concurrent downloads.
 * Setters persist first, then emit; writes come from the UI thread.
 */
class SettingsStore(private val store: KeyValueStore) {
    private val _wifiOnly = MutableStateFlow(store.getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _mirror = MutableStateFlow(store.getString(KEY_MIRROR) ?: DEFAULT_MIRROR)
    val mirror: StateFlow<String> = _mirror.asStateFlow()

    private val _concurrentDownloads =
        MutableStateFlow(store.getInt(KEY_CONCURRENT_DOWNLOADS, DEFAULT_CONCURRENT_DOWNLOADS))
    val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    fun setWifiOnly(enabled: Boolean) {
        store.putBoolean(KEY_WIFI_ONLY, enabled)
        _wifiOnly.value = enabled
    }

    fun setMirror(url: String) {
        store.putString(KEY_MIRROR, url)
        _mirror.value = url
    }

    /** The Settings screen exposes a 1–5 slider (Task 9); clamping keeps hand-edited values safe. */
    fun setConcurrentDownloads(count: Int) {
        val clamped = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        store.putInt(KEY_CONCURRENT_DOWNLOADS, clamped)
        _concurrentDownloads.value = clamped
    }

    private companion object {
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
