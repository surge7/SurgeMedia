package com.surgemusic.app.settings

import android.content.SharedPreferences

/**
 * Tiny key/value abstraction (plan Task 7 Step 2) so [SettingsStore] can be unit-tested
 * Robolectric-free against an in-memory fake; [SharedPreferencesStore] is the production impl.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

/** [KeyValueStore] backed by [SharedPreferences]. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}
