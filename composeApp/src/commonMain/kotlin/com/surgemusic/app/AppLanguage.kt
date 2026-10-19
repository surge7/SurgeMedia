package com.surgemusic.app

/**
 * Language preference (Settings ▸ Appearance): follow the OS, or force English / Russian.
 * Persisted as `language` by both platform settings stores. Mirrors [ThemeMode].
 */
enum class AppLanguage { SYSTEM, ENGLISH, RUSSIAN }

/** Parse a persisted [AppLanguage] name; anything unknown (or null) falls back to [AppLanguage.SYSTEM]. */
fun appLanguageOf(raw: String?): AppLanguage =
    AppLanguage.entries.firstOrNull { it.name == raw } ?: AppLanguage.SYSTEM

/** True when the OS locale is Russian — drives the SYSTEM language default. */
expect fun systemLanguageIsRussian(): Boolean
