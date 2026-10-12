package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchPageParserTest {

    private fun fixture(): String =
        javaClass.getResource("/fixtures/search_two_rows.html")!!.readText()

    @Test fun parsesAllRows() {
        val page = SearchPageParser.parse(fixture(), start = 0)
        assertEquals(2, page.rows.size)
        assertEquals(0, page.start)
        val first = page.rows[0]
        assertEquals(567890L, first.topicId)
        assertEquals("Pink Floyd - The Dark Side of the Moon (1973) [FLAC<Remaster>]", first.title)
        assertEquals(1234L, first.forumId)
        assertEquals("Музыка (lossless)", first.forumName)
        assertEquals(SizeParser.parseBytes("1.36 ГБ"), first.sizeBytes)
        assertEquals(34, first.seeds)
        assertEquals(2, first.leechs)
        assertEquals("2024-05-01 12:33", first.dateText)
        val second = page.rows[1]
        assertEquals(991002L, second.topicId)
        assertEquals(12, second.seeds)
        assertEquals(0, second.leechs)
    }

    @Test fun loginFormPageThrowsAuth() {
        val err = assertFailsWith<TrackerError> {
            SearchPageParser.parse("""<html><form id="frm-login" action="login.php"></form></html>""", start = 0)
        }
        assertEquals(TrackerErrorKind.AUTH, err.kind)
    }

    @Test fun unknownMarkupThrowsParseErrorNamingSelector() {
        val err = assertFailsWith<TrackerError> { SearchPageParser.parse("<html><body>garbage</body></html>", start = 0) }
        assertEquals(TrackerErrorKind.PARSE, err.kind)
        assertTrue(err.selector.isNullOrEmpty().not())
    }
}
