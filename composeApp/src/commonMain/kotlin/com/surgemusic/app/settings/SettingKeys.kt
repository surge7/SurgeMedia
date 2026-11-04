package com.surgemusic.app.settings

/**
 * Settings storage keys and defaults shared by both platform stores (desktop
 * `DesktopSettingsStore`, androidMain `SettingsStore`) so the two stay in lockstep.
 *
 * Cross-platform portability: both stores persist under these exact keys and fall back to
 * these exact defaults — desktop as `java.util.Properties` at
 * `~/.surgemusic/settings.properties`, android as SharedPreferences — so a hand-copied
 * settings file ports between platforms.
 */
object SettingKeys {
    const val WIFI_ONLY = "wifi_only"
    const val MIRROR = "mirror"
    const val CONCURRENT_DOWNLOADS = "concurrent_downloads"
    const val THEME_MODE = "theme_mode"
    const val LANGUAGE = "language"
    const val MUSIC_FOLDER = "music_folder"
    const val VIDEO_FOLDER = "video_folder"

    /** Wi-Fi only = on. */
    const val DEFAULT_WIFI_ONLY = true

    /** Mirror = `https://rutracker.org`. */
    const val DEFAULT_MIRROR = "https://rutracker.org"

    /** 3 concurrent downloads. */
    const val DEFAULT_CONCURRENT_DOWNLOADS = 3
}
