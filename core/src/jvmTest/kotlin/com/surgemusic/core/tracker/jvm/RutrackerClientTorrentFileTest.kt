package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RutrackerClientTorrentFileTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { if (this::client.isInitialized) client.close(); server.shutdown() }

    private suspend fun loggedInClient(): RutrackerClient {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "bb_data=ok").setBody("<html>ok</html>"))
        val c = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }
        assertTrue(c.login("user", "pass").isSuccess)
        return c
    }

    @Test fun returnsTorrentBytes() = runTest {
        val c = loggedInClient()
        val torrentBytes = "d8:announce29:https://bt.t-ru.org/announce10:info.dict4:infod6:lengthi1024eee"
            .toByteArray(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setBody(Buffer().write(torrentBytes)))
        val result = c.torrentFile(567890L)
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertContentEquals(torrentBytes, result.getOrNull())
        // MockWebServer 4.12.0's takeRequest pops from the head — consume the login request
        // first (same adaptation as RutrackerClientSearchTest), then inspect the dl.php request.
        server.takeRequest()
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/forum/dl.php"))
        assertTrue(req.path!!.contains("t=567890"))
    }

    @Test fun htmlInsteadOfTorrentIsAuthError() = runTest {
        val c = loggedInClient()
        server.enqueue(MockResponse().setBody("""<html><form id="frm-login" action="login.php"></form></html>"""))
        val result = c.torrentFile(1L)
        assertTrue(result.isFailure)
        assertEquals(TrackerErrorKind.AUTH, (result.exceptionOrNull() as TrackerError).kind)
    }

    @Test fun unexpectedHtmlIsParseError() = runTest {
        val c = loggedInClient()
        server.enqueue(MockResponse().setBody("<html>some other page</html>"))
        val result = c.torrentFile(1L)
        assertEquals(TrackerErrorKind.PARSE, (result.exceptionOrNull() as TrackerError).kind)
    }
}
