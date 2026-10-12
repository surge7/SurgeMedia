package com.surgemusic.core.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeParserTest {
    @Test fun parsesRussianUnits() {
        assertEquals(1L, SizeParser.parseBytes("1 Б"))
        assertEquals(2048L, SizeParser.parseBytes("2 КБ"))
        assertEquals((1.36 * 1024 * 1024 * 1024).toLong(), SizeParser.parseBytes("1.36 ГБ"))
        assertEquals(742 * 1024 * 1024L, SizeParser.parseBytes("742 МБ"))
        assertEquals(2L * 1024 * 1024 * 1024 * 1024, SizeParser.parseBytes("2 ТБ"))
    }
    @Test fun parsesEnglishUnitsAndNbsp() {
        assertEquals((1.5 * 1024 * 1024).toLong(), SizeParser.parseBytes("1.5\u00A0MB"))
        assertEquals(512L * 1024, SizeParser.parseBytes("512.00 KB"))
    }
    @Test fun parsesCommaDecimals() {
        assertEquals((1.25 * 1024).toLong(), SizeParser.parseBytes("1,25 КБ"))
    }
    @Test fun parsesTwoPartRange() {
        // rutracker sometimes renders "700 МБ+700 МБ" style; take the first token
        assertEquals(700L * 1024 * 1024, SizeParser.parseBytes("700 МБ + 700 МБ"))
    }
    @Test fun formatsRoundTripWithinOnePercent() {
        // formatBytes is a display formatter: exact round-trip is impossible for
        // arbitrary byte counts (2 fixed decimals), so we assert 1% tolerance —
        // plus exactness where the format is integral by construction.
        val samples = listOf(512L * 1024, 1_468_003_328L, 742L * 1024 * 1024, 3L)
        samples.forEach { b ->
            val text = SizeParser.formatBytes(b)
            val back = SizeParser.parseBytes(text)
            assertTrue(kotlin.math.abs(back - b) <= b / 100, "formatBytes($b) -> \"$text\" -> $back (off by more than 1%)")
        }
        assertEquals(512L * 1024, SizeParser.parseBytes(SizeParser.formatBytes(512L * 1024)))
    }
}
