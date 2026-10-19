package com.surgemusic.app

import com.surgemusic.app.ui.strings.EnStrings
import com.surgemusic.app.ui.strings.RuStrings
import com.surgemusic.app.ui.strings.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [appLanguageOf] parsing/fallback and the [stringsFor] matrix, plus the Russian plural
 * forms surfaced through [RuStrings] (one/few/many, and the 11–14 «many» exception).
 */
class AppLanguageTest {

    @Test
    fun parsesEnumNames() {
        assertEquals(AppLanguage.SYSTEM, appLanguageOf("SYSTEM"))
        assertEquals(AppLanguage.ENGLISH, appLanguageOf("ENGLISH"))
        assertEquals(AppLanguage.RUSSIAN, appLanguageOf("RUSSIAN"))
    }

    @Test
    fun nullAndUnknownFallBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, appLanguageOf(null))
        assertEquals(AppLanguage.SYSTEM, appLanguageOf(""))
        assertEquals(AppLanguage.SYSTEM, appLanguageOf("klingon"))
    }

    @Test
    fun stringsForMatrix() {
        assertEquals(EnStrings, stringsFor(AppLanguage.ENGLISH, systemIsRussian = true))
        assertEquals(EnStrings, stringsFor(AppLanguage.ENGLISH, systemIsRussian = false))
        assertEquals(RuStrings, stringsFor(AppLanguage.RUSSIAN, systemIsRussian = true))
        assertEquals(RuStrings, stringsFor(AppLanguage.RUSSIAN, systemIsRussian = false))
        assertEquals(RuStrings, stringsFor(AppLanguage.SYSTEM, systemIsRussian = true))
        assertEquals(EnStrings, stringsFor(AppLanguage.SYSTEM, systemIsRussian = false))
    }

    @Test
    fun russianPluralForms() {
        assertEquals("Удалён 1 файл", RuStrings.deletedFiles(1))
        assertEquals("Удалено 2 файла", RuStrings.deletedFiles(2))
        assertEquals("Удалено 5 файлов", RuStrings.deletedFiles(5))
        assertEquals("Удалён 21 файл", RuStrings.deletedFiles(21))
        assertEquals("Удалено 11 файлов", RuStrings.deletedFiles(11)) // 11–14 are «many»

        assertEquals("1 пир", RuStrings.peersCount(1))
        assertEquals("3 пира", RuStrings.peersCount(3))
        assertEquals("5 пиров", RuStrings.peersCount(5))
    }

    @Test
    fun englishCountsStayVerbatim() {
        assertEquals("Deleted 3 file(s)", EnStrings.deletedFiles(3))
        assertEquals("3 peers", EnStrings.peersCount(3))
        assertEquals("2.5 MB/s", EnStrings.speedPerSecond("2.5 MB"))
    }
}
