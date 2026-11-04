package com.surgemusic.app.settings

import com.surgemusic.app.AppLanguage
import com.surgemusic.app.ThemeMode
import com.surgemusic.app.appLanguageOf
import com.surgemusic.app.themeModeOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App settings (plan Task 7): SharedPreferences-backed [StateFlow]s.
 * Defaults: Wi-Fi only = on, mirror = `https://rutracker.org`, 3 concurrent downloads,
 * theme = System, language = System. Setters persist first, then emit; writes come from
 * the UI thread. Implements the commonMain [AppSettings] slice so the Settings screen
 * (Task 9) drives it.
 */
class SettingsStore(private val store: KeyValueStore) : AppSettings {
    private val _wifiOnly = MutableStateFlow(store.getBoolean(SettingKeys.WIFI_ONLY, SettingKeys.DEFAULT_WIFI_ONLY))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _mirror = MutableStateFlow(store.getString(SettingKeys.MIRROR) ?: SettingKeys.DEFAULT_MIRROR)
    override val mirror: StateFlow<String> = _mirror.asStateFlow()

    private val _concurrentDownloads =
        MutableStateFlow(store.getInt(SettingKeys.CONCURRENT_DOWNLOADS, SettingKeys.DEFAULT_CONCURRENT_DOWNLOADS))
    override val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    private val _themeMode = MutableStateFlow(themeModeOf(store.getString(SettingKeys.THEME_MODE)))
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language = MutableStateFlow(appLanguageOf(store.getString(SettingKeys.LANGUAGE)))
    override val language: StateFlow<AppLanguage> = _language.asStateFlow()

    // SAF tree URIs (Settings ▸ Storage), "" = built-in default destination.
    private val _musicFolder = MutableStateFlow(store.getString(SettingKeys.MUSIC_FOLDER) ?: "")
    override val musicFolder: StateFlow<String> = _musicFolder.asStateFlow()

    private val _videoFolder = MutableStateFlow(store.getString(SettingKeys.VIDEO_FOLDER) ?: "")
    override val videoFolder: StateFlow<String> = _videoFolder.asStateFlow()

    override val musicFolderDefaultLabel: String = "Music/$BRAND_DIR"
    override val videoFolderDefaultLabel: String = "Movies/$BRAND_DIR"

    override fun setWifiOnly(enabled: Boolean) {
        store.putBoolean(SettingKeys.WIFI_ONLY, enabled)
        _wifiOnly.value = enabled
    }

    override fun setMirror(url: String) {
        store.putString(SettingKeys.MIRROR, url)
        _mirror.value = url
    }

    /** The Settings screen exposes a 1–5 slider (Task 9); clamping keeps hand-edited values safe. */
    override fun setConcurrentDownloads(count: Int) {
        val clamped = count.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        store.putInt(SettingKeys.CONCURRENT_DOWNLOADS, clamped)
        _concurrentDownloads.value = clamped
    }

    override fun setThemeMode(mode: ThemeMode) {
        store.putString(SettingKeys.THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    override fun setLanguage(language: AppLanguage) {
        store.putString(SettingKeys.LANGUAGE, language.name)
        _language.value = language
    }

    override fun setMusicFolder(path: String) {
        store.putString(SettingKeys.MUSIC_FOLDER, path)
        _musicFolder.value = path
    }

    override fun setVideoFolder(path: String) {
        store.putString(SettingKeys.VIDEO_FOLDER, path)
        _videoFolder.value = path
    }

    private companion object {
        const val BRAND_DIR = "SurgeMedia"
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 5
    }
}
