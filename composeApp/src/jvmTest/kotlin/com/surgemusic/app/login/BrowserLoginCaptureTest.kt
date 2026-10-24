package com.surgemusic.app.login

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BrowserLoginCapture] coverage:
 *
 * - Pure pieces (domain matching, header assembly, CDP reply parsing) run everywhere.
 * - `capturesCookiesFromRealChromium` runs the real path — launch → /json/version UA →
 *   page websocket → Network.getAllCookies → header assembly — against a local HTTP
 *   server that sets the tracker cookies. Gated on a Chromium browser being installed
 *   (`CHROME_BIN` overrides the search); runs headless (`--headless=new`) so it works
 *   on windowless CI runners too.
 */
class BrowserLoginCaptureTest {

    @Test
    fun cookieDomainMatching() {
        assertTrue(domainMatches("rutracker.org", "rutracker.org"))
        assertTrue(domainMatches(".rutracker.org", "rutracker.org"))
        assertTrue(domainMatches(".rutracker.org", "www.rutracker.org"))
        assertTrue(domainMatches("RUTRACKER.org", "rutracker.org"), "case-insensitive")
        assertTrue(domainMatches("127.0.0.1", "127.0.0.1"), "host-only cookie (no Domain attr)")
        assertNullDomain("")
        assertFalseMatch("rutracker.org", "notrutracker.org")
        assertFalseMatch("tracker.org", "rutracker.org.evil.com")
    }

    private fun assertNullDomain(domain: String) = assertTrue(!domainMatches(domain, "rutracker.org"))
    private fun assertFalseMatch(domain: String, host: String) = assertTrue(!domainMatches(domain, host))

    @Test
    fun headerAssemblyKeepsOnlyTrackerCookiesInOrder() {
        val cookies = listOf(
            CdpCookie("bb_guid", "g", ".rutracker.org"),
            CdpCookie("other", "x", "google.com"),
            CdpCookie("bb_session", "s", "rutracker.org"),
            CdpCookie("cf_clearance", "c", ".rutracker.org"),
            CdpCookie("session", "foreign", "sub.rutracker.org.evil.com"),
        )
        assertEquals(
            "bb_guid=g; bb_session=s; cf_clearance=c",
            assembleCookieHeader(cookies, "rutracker.org"),
        )
        assertEquals("", assembleCookieHeader(cookies, "example.org"))
    }

    @Test
    fun parsesCdpCookieReplies() {
        val reply = """
            {"id":2,"result":{"cookies":[
              {"name":"bb_session","value":"abc","domain":".rutracker.org","path":"/","expires":9999},
              {"name":"cf_clearance","value":"xyz","domain":".rutracker.org","path":"/"}
            ]}}
        """.trimIndent()
        assertEquals(
            listOf(CdpCookie("bb_session", "abc", ".rutracker.org"), CdpCookie("cf_clearance", "xyz", ".rutracker.org")),
            BrowserLoginCapture.parseCookieResult(reply),
        )
        // Events and error replies carry no result.cookies → null (caller keeps polling).
        assertNull(BrowserLoginCapture.parseCookieResult("""{"method":"Network.requestWillBeSent","params":{}}"""))
        assertNull(BrowserLoginCapture.parseCookieResult("""{"id":2,"error":{"code":-32601,"message":"not found"}}"""))
    }

    @Test
    fun capturesCookiesFromRealChromium() {
        val browser = BrowserLoginCapture.findChromiumBrowser()
        assumeTrue(
            "no Chromium browser found (set CHROME_BIN to force) — skipping real capture test",
            browser != null || System.getenv("CHROME_BIN") != null,
        )
        val resolved = browser ?: Path.of(System.getenv("CHROME_BIN"))
        val server = cookieServer("bb_session=cdp-test-session; Path=/", "cf_clearance=cdp-test-clearance; Path=/")
        try {
            val port = server.localPort
            val statuses = mutableListOf<String>()
            val result = runBlocking {
                withTimeout(TimeUnit.SECONDS.toMillis(120)) {
                    BrowserLoginCapture.capture(
                        browser = resolved,
                        loginUrl = "http://127.0.0.1:$port/forum/login.php",
                        extraArgs = listOf("--headless=new", "--disable-gpu", "--no-sandbox"),
                        timeout = java.time.Duration.ofSeconds(90),
                        onStatus = { statuses.add(it) },
                    )
                }
            }
            val session = (result as? BrowserLoginCapture.Result.Success)?.session
            assertNotNull(session, "capture should succeed, got $result (statuses=$statuses)")
            assertTrue("bb_session=cdp-test-session" in session.cookieHeader, "cookie header: ${session.cookieHeader}")
            assertTrue("cf_clearance=cdp-test-clearance" in session.cookieHeader, "cookie header: ${session.cookieHeader}")
            assertTrue("Chrome" in session.userAgent, "user agent: ${session.userAgent}")
        } finally {
            server.close()
        }
    }

    /** Minimal HTTP server: every response sets the given cookies, then closes. */
    private fun cookieServer(vararg setCookies: String): ServerSocket {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "cookie-server") {
            while (!server.isClosed) {
                val socket: Socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                socket.use { s ->
                    runCatching {
                        s.soTimeout = 2000
                        // Drain the request (a GET fits in one read; favicon probes too).
                        runCatching { s.getInputStream().read(ByteArray(8192)) }
                        val body = "ok"
                        val cookieHeaders = setCookies.joinToString("") { "Set-Cookie: $it\r\n" }
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                            cookieHeaders +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        s.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
        }
        return server
    }
}
