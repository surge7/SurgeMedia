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
        assertEquals(6918919L, first.topicId)
        assertEquals(
            "Pink Floyd - Meddle (2016 Remix) [2026, Remaster] - 1971/2016/2026, FLAC (tracks), lossless",
            first.title,
        )
        assertEquals(134L, first.forumId)
        assertEquals("Неофициальные сборники и ремастеринги (lossless)", first.forumName)
        // live markup carries exact bytes in data-ts_text — no unit parsing needed
        assertEquals(254391132L, first.sizeBytes)
        assertEquals(9, first.seeds)
        assertEquals(1, first.leechs)
        assertEquals("6-Окт-26", first.dateText)
        val second = page.rows[1]
        assertEquals(6918740L, second.topicId)
        assertEquals(
            "Pink Floyd - Live from the Oakland–Alameda County Coliseum [2026, Remaster] - 1977/2009/2026, FLAC (tracks), lossless",
            second.title,
        )
        assertEquals(644668133L, second.sizeBytes)
        assertEquals(11, second.seeds)
        assertEquals(1, second.leechs)
        assertEquals("5-Окт-26", second.dateText)
    }

    @Test fun fallsBackToSizeTextWhenDataAttrMissing() {
        // older/mirror markup without data-ts_text: parse the visible "242.6 МБ"-style text
        val html = """
            <html><body><table id="tor-tbl"><tbody>
            <tr class="tCenter">
              <td class="row4 f-name-col"><div class="f-name"><a href="tracker.php?f=9">Музыка (MP3)</a></div></td>
              <td class="row4 t-title-col"><div class="t-title"><a class="tLink tt-text" href="viewtopic.php?t=42">Some Album (MP3)</a></div></td>
              <td class="row4 small nowrap tor-size"><a class="tr-dl" href="dl.php?t=42">742.32&nbsp;МБ ↓</a></td>
              <td class="row4 nowrap"><b class="seedmed">3</b></td>
              <td class="row4 leechmed bold">0</td>
              <td class="row4 small">1-Янв-26</td>
            </tr>
            </tbody></table></body></html>
        """.trimIndent()
        val page = SearchPageParser.parse(html, start = 0)
        assertEquals(1, page.rows.size)
        assertEquals(SizeParser.parseBytes("742.32 МБ"), page.rows[0].sizeBytes)
        assertEquals(42L, page.rows[0].topicId)
        assertEquals(3, page.rows[0].seeds)
        assertEquals(0, page.rows[0].leechs)
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
