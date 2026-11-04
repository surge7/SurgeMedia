package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RutrackerClientLoginTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { if (this::client.isInitialized) client.close(); server.shutdown() }

    private fun newClient() = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }

    private fun fixture() = javaClass.getResource("/fixtures/search_two_rows.html")!!.readText()

    @Test fun loginCapturesBbSessionCookie() = runTest {
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "bb_session=abc123; path=/forum/; domain=.rutracker.org")
                .setBody("<html><body>logged in</body></html>")
        )
        val result = newClient().login("user", "pass")
        assertTrue(result.isSuccess, "login should succeed: ${result.exceptionOrNull()}")
        val req = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/forum/login.php", req.path?.substringBefore('?'))
        assertTrue(req.body.readUtf8().contains("login_username=user"))
    }

    @Test fun loginCapturesLegacyBbDataCookie() = runTest {
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "bb_data=legacy99; path=/forum/; domain=.rutracker.org")
                .setBody("<html><body>logged in</body></html>")
        )
        server.enqueue(MockResponse().setBody(fixture()))
        val c = newClient()
        val result = c.login("user", "pass")
        assertTrue(result.isSuccess, "legacy bb_data login should succeed: ${result.exceptionOrNull()}")

        // The matched cookie NAME must survive the login: the next request carries the
        // captured bb_data cookie, not a hardcoded bb_session= rewrite of it.
        val search = c.search(SearchQuery(text = "x"))
        assertTrue(search.isSuccess, "search after bb_data login failed: ${search.exceptionOrNull()}")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) // login request (head of queue)
        val searchReq = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("bb_data=legacy99", searchReq.headers["Cookie"])
    }

    @Test fun cloudflareChallengeOnLoginPageIsAuthErrorWithGuidance() = runTest {
        server.enqueue(MockResponse().setBody("<html><title>Just a moment...</title>challenge</html>"))
        val result = newClient().login("user", "pass")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.AUTH, err.kind)
        assertTrue(err.message!!.contains("import a browser session"))
    }

    @Test fun loginFailureWithoutCookieIsAuthError() = runTest {
        server.enqueue(
            MockResponse().setBody("""<html><form id="frm-login" action="login.php"><span class="error">Неверный пароль</span></form></html>""")
        )
        val result = newClient().login("user", "wrong")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.AUTH, err.kind)
    }

    @Test fun captchaPageIsCaptchaError() = runTest {
        server.enqueue(MockResponse().setBody("<html>please complete the CAPTCHA</html>"))
        val result = newClient().login("user", "pass")
        val err = result.exceptionOrNull() as TrackerError
        assertEquals(TrackerErrorKind.CAPTCHA, err.kind)
    }
}
