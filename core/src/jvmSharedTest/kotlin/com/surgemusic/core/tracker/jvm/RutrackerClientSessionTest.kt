package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Cloudflare-era session mode: a raw Cookie header harvested from a logged-in
 * browser (bb_session + cf_clearance + …), together with the User-Agent it was
 * harvested with. Verified live on 2026-10-12 — see spec §4 "Cloudflare reality".
 */
class RutrackerClientSessionTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { if (this::client.isInitialized) client.close(); server.shutdown() }

    private fun newClient() = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }

    private fun fixture() = javaClass.getResource("/fixtures/search_two_rows.html")!!.readText()

    @Test fun importedSessionSendsRawCookieAndHarvestedUserAgent() = runTest {
        server.enqueue(MockResponse().setBody(fixture()))
        val c = newClient()
        c.importSession(
            cookieHeader = "bb_session=x1; cf_clearance=y2; bb_guid=g3; bb_ssl=1",
            harvestedUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36",
        )
        val result = c.search(SearchQuery(text = "pink floyd"))
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertEquals(2, result.getOrNull()!!.rows.size)

        val req = server.takeRequest()
        assertEquals("bb_session=x1; cf_clearance=y2; bb_guid=g3; bb_ssl=1", req.headers["Cookie"])
        assertTrue(req.headers["User-Agent"]!!.contains("Chrome/155"))
    }

    @Test fun importedSessionExpiredRedirectToLoginIsAuthError() = runTest {
        // rutracker redirects logged-out requests: 302 -> /forum/login.php?redirect=tracker.php…
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/forum/login.php").toString() + "?redirect=tracker.php%3Fnm%3Dx")
        )
        server.enqueue(MockResponse().setBody("""<html><form id="frm-login" action="login.php"></form></html>"""))
        val c = newClient()
        c.importSession(cookieHeader = "bb_session=stale")
        val result = c.search(SearchQuery(text = "x"))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.AUTH, err.kind)
        assertTrue(err.message!!.contains("session expired"))
    }

    @Test fun cloudflareChallengeInsteadOfResultsIsAuthError() = runTest {
        server.enqueue(MockResponse().setBody("<html><title>Just a moment...</title>cf-chl challenge</html>"))
        val c = newClient()
        c.importSession(cookieHeader = "bb_session=x")
        val result = c.search(SearchQuery(text = "x"))
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.AUTH, err.kind)
        assertTrue(err.message!!.contains("re-import"))
    }

    @Test fun searchDecodesWindows1251Response() = runTest {
        // rutracker serves text/html; charset=Windows-1251 — Cyrillic must survive the decode
        val html = fixture().replace("<meta charset=\"utf-8\">", "<meta charset=\"Windows-1251\">")
        val bytes = html.toByteArray(charset("windows-1251"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=Windows-1251")
                .setBody(Buffer().write(bytes))
        )
        val c = newClient()
        c.importSession(cookieHeader = "bb_session=x")
        val result = c.search(SearchQuery(text = "pink floyd"))
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        val rows = result.getOrNull()!!.rows
        assertEquals("Неофициальные сборники и ремастеринги (lossless)", rows[0].forumName)
        assertEquals("6-Окт-26", rows[0].dateText)
    }
}
