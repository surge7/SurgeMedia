package com.surgemusic.app.settings

import com.surgemusic.app.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SettingsStore] against an in-memory [KeyValueStore] fake (plan Task 7 Step 2 —
 * Robolectric-free). Defaults + update emission + persistence via a fresh instance over
 * the same backing store. The SharedPreferences adapter itself is emulator-verified.
 */
class SettingsStoreTest {

    private class InMemoryKeyValueStore : KeyValueStore {
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()

        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }
    }

    @Test
    fun defaultsWhenStoreIsEmpty() {
        val settings = SettingsStore(InMemoryKeyValueStore())

        assertEquals(true, settings.wifiOnly.value)
        assertEquals("https://rutracker.org", settings.mirror.value)
        assertEquals(3, settings.concurrentDownloads.value)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
    }

    @Test
    fun updatesEmitAndSurviveReload() {
        val store = InMemoryKeyValueStore()
        val settings = SettingsStore(store)

        settings.setWifiOnly(false)
        settings.setMirror("https://rutracker.net")
        settings.setConcurrentDownloads(5)
        settings.setThemeMode(ThemeMode.LIGHT)

        assertEquals(false, settings.wifiOnly.value)
        assertEquals("https://rutracker.net", settings.mirror.value)
        assertEquals(5, settings.concurrentDownloads.value)
        assertEquals(ThemeMode.LIGHT, settings.themeMode.value)

        val reloaded = SettingsStore(store) // same backing store, fresh instance
        assertEquals(false, reloaded.wifiOnly.value)
        assertEquals("https://rutracker.net", reloaded.mirror.value)
        assertEquals(5, reloaded.concurrentDownloads.value)
        assertEquals(ThemeMode.LIGHT, reloaded.themeMode.value)
    }

    @Test
    fun unknownPersistedThemeModeFallsBackToSystem() {
        val store = InMemoryKeyValueStore()
        store.putString("theme_mode", "SEPIA") // hand-edited / from a future build

        assertEquals(ThemeMode.SYSTEM, SettingsStore(store).themeMode.value)
    }

    @Test
    fun concurrentDownloadsIsClampedToOneToFive() {
        val store = InMemoryKeyValueStore()
        val settings = SettingsStore(store)

        settings.setConcurrentDownloads(0)
        assertEquals(1, settings.concurrentDownloads.value)

        settings.setConcurrentDownloads(9)
        assertEquals(5, settings.concurrentDownloads.value)
    }
}
