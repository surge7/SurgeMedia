package com.surgemusic.app.settings

import com.surgemusic.app.AppLanguage
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DesktopSettingsStore] language key against a throwaway home dir — the desktop twin of
 * androidUnitTest's [SettingsStoreTest] language assertions. The Properties flush is async
 * on a daemon thread, so the reload assertion polls until the snapshot lands on disk.
 */
class DesktopSettingsStoreTest {

    @Test
    fun languageDefaultsToSystemAndRoundTrips() {
        val home = createTempDirectory("surge-settings-test")
        try {
            val store = DesktopSettingsStore(home)
            assertEquals(AppLanguage.SYSTEM, store.language.value)

            store.setLanguage(AppLanguage.RUSSIAN)
            assertEquals(AppLanguage.RUSSIAN, store.language.value)

            store.setLanguage(AppLanguage.ENGLISH)
            assertEquals(AppLanguage.ENGLISH, store.language.value)

            // Async flush: poll a fresh instance over the same home until the write lands.
            var reloaded: DesktopSettingsStore
            val deadline = System.currentTimeMillis() + 5_000
            do {
                reloaded = DesktopSettingsStore(home)
                if (reloaded.language.value == AppLanguage.ENGLISH) break
                Thread.sleep(25)
            } while (System.currentTimeMillis() < deadline)
            assertEquals(AppLanguage.ENGLISH, reloaded.language.value)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownPersistedLanguageFallsBackToSystem() {
        val home = createTempDirectory("surge-settings-test")
        try {
            val dir = home.resolve(".surgemusic")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("settings.properties"), "language=KLINGON\n")

            assertEquals(AppLanguage.SYSTEM, DesktopSettingsStore(home).language.value)
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
