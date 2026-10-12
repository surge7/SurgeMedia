package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RutrackerClientSearchTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { if (this::client.isInitialized) client.close(); server.shutdown() }

    private fun fixture() = javaClass.getResource("/fixtures/search_two_rows.html")!!.readText()

    @Test fun searchParsesResultsAndSendsCookie() = runTest {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "bb_session=ok").setBody("<html>ok</html>"))
        server.enqueue(MockResponse().setBody(fixture()))
        val c = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }
        assertTrue(c.login("user", "pass").isSuccess)

        val result = c.search(SearchQuery(text = "pink floyd", start = 0))
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertEquals(2, result.getOrNull()!!.rows.size)

        // MockWebServer 4.12.0 has no takeRequest(long) overload, and even if it did,
        // takeRequest pops from the head — so consume the login request first, then take
        // the search request (the plan's "// skip the login request" intent).
        server.takeRequest()
        val searchReq = server.takeRequest()
        assertTrue(searchReq.path!!.startsWith("/forum/tracker.php"))
        assertTrue(searchReq.path!!.contains("nm=pink+floyd") || searchReq.path!!.contains("nm=pink%20floyd"))
        assertTrue(searchReq.headers["Cookie"]!!.contains("bb_session=ok"))
        assertTrue(searchReq.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test fun searchWithoutLoginFailsClosed() = runTest {
        val c = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }
        val result = c.search(SearchQuery(text = "x"))
        assertTrue(result.isFailure)
        assertEquals(TrackerErrorKind.AUTH, (result.exceptionOrNull() as TrackerError).kind)
    }
}
