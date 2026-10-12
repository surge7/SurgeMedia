package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RutrackerClientLoginTest {
    private val server = MockWebServer()
    private lateinit var client: RutrackerClient

    @AfterTest fun tearDown() { if (this::client.isInitialized) client.close(); server.shutdown() }

    private fun newClient() = RutrackerClient(baseUrl = server.url("/").toString().trimEnd('/')).also { client = it }

    @Test fun loginCapturesBbDataCookie() = runTest {
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "bb_data=abc123; path=/; domain=.rutracker.org")
                .setBody("<html><body>logged in</body></html>")
        )
        val result = newClient().login("user", "pass")
        assertTrue(result.isSuccess, "login should succeed: ${result.exceptionOrNull()}")
        val req = server.takeRequest()
        assertEquals("/forum/login.php", req.path?.substringBefore('?'))
        assertTrue(req.body.readUtf8().contains("login_username=user"))
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
