package com.surgemusic.app.locale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [DesktopLocaleMonitor]'s pure change-detection core: [detectLocaleChange] over injected
 * prior/new locale samples, and [languageOfOsTag] parsing of the OS tags (macOS
 * AppleLocale, Windows `LCIDToLocaleName` output). The OS-sourced sample wins over the
 * JVM's own movement; the JVM-only signal still triggers a re-resolve.
 */
class DesktopLocaleMonitorTest {

    // --- detectLocaleChange ---

    @Test
    fun osChangeWinsOverJvmMovement() {
        assertEquals(LocaleChange.OsChange("en"), detectLocaleChange(priorLanguage = "ru", osLanguage = "en", jvmLanguage = "ru"))
        // OS and JVM both moved: the OS-sourced sample wins (it carries the setDefault).
        assertEquals(LocaleChange.OsChange("en"), detectLocaleChange(priorLanguage = "ru", osLanguage = "en", jvmLanguage = "en"))
    }

    @Test
    fun jvmSelfUpdateDetectedWithoutOsSample() {
        // Linux-shaped poll: no OS sample at all, JVM default moved on its own.
        assertEquals(LocaleChange.JvmSelfUpdate("en"), detectLocaleChange(priorLanguage = "ru", osLanguage = null, jvmLanguage = "en"))
        // macOS-shaped poll: the JVM self-updated before the OS committed its change.
        assertEquals(LocaleChange.JvmSelfUpdate("en"), detectLocaleChange(priorLanguage = "ru", osLanguage = "ru", jvmLanguage = "en"))
    }

    @Test
    fun noChangeWhenSamplesMatchBaseline() {
        assertEquals(LocaleChange.None, detectLocaleChange(priorLanguage = "ru", osLanguage = "ru", jvmLanguage = "ru"))
        assertEquals(LocaleChange.None, detectLocaleChange(priorLanguage = "ru", osLanguage = null, jvmLanguage = "ru"))
        assertEquals(LocaleChange.None, detectLocaleChange(priorLanguage = "ru", osLanguage = null, jvmLanguage = null))
    }

    // --- languageOfOsTag ---

    @Test
    fun parsesAppleLocaleTags() {
        assertEquals("ru", languageOfOsTag("ru_RU"))
        assertEquals("ru", languageOfOsTag("ru_RU@rg=uszzzz"))
        assertEquals("en", languageOfOsTag("en_US@calendar=gregorian"))
        assertEquals("en", languageOfOsTag("en_US"))
        assertEquals("de", languageOfOsTag("de"))
    }

    @Test
    fun parsesWindowsLocaleNameTags() {
        assertEquals("ru", languageOfOsTag("ru-RU"))
        assertEquals("en", languageOfOsTag("en-US"))
    }

    @Test
    fun onlyTheFirstLineCounts() {
        // `defaults read` appends a trailing newline — it must not break the parse.
        assertEquals("ru", languageOfOsTag("ru_RU\n"))
        assertEquals("en", languageOfOsTag("en_US@rg=uszzzz\nand more"))
    }

    @Test
    fun malformedTagsReturnNull() {
        assertNull(languageOfOsTag(null))
        assertNull(languageOfOsTag(""))
        assertNull(languageOfOsTag("   "))
        assertNull(languageOfOsTag("@rg=uszzzz"))
        assertNull(languageOfOsTag("12_34"))
        // Lenient on trailing junk — only the language head matters:
        assertEquals("ru", languageOfOsTag("ru_RU extra"))
    }
}
