package com.surgemusic.app.settings

import com.surgemusic.app.AppLanguage
import com.surgemusic.app.ThemeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of app settings the Settings tab drives (plan Task 9). Lives in commonMain so
 * the screen compiles there; androidMain's `SettingsStore` implements it directly.
 *
 * Storage: [musicFolder] / [videoFolder] hold the user-chosen destination for finished
 * downloads — a platform-specific path (desktop: absolute filesystem path; android: a
 * SAF tree URI), or "" for the built-in default (whose display label the
 * `*FolderDefaultLabel` vals carry). Folder values are opaque to commonMain; only the
 * platform finalizers interpret them.
 */
interface AppSettings {
    val wifiOnly: StateFlow<Boolean>
    val mirror: StateFlow<String>
    val concurrentDownloads: StateFlow<Int>
    val themeMode: StateFlow<ThemeMode>
    val language: StateFlow<AppLanguage>
    val musicFolder: StateFlow<String>
    val videoFolder: StateFlow<String>

    /** Display label of the built-in music destination (platform-specific path). */
    val musicFolderDefaultLabel: String

    /** Display label of the built-in video destination (platform-specific path). */
    val videoFolderDefaultLabel: String

    fun setWifiOnly(enabled: Boolean)

    fun setMirror(url: String)

    fun setConcurrentDownloads(count: Int)

    fun setThemeMode(mode: ThemeMode)

    fun setLanguage(language: AppLanguage)

    fun setMusicFolder(path: String)

    fun setVideoFolder(path: String)
}
