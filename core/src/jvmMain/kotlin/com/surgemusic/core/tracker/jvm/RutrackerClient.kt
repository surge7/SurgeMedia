package com.surgemusic.core.tracker.jvm

import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultPage
import com.surgemusic.core.tracker.TrackerClient
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.TrackerErrorKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * rutracker.org client.
 *
 * Two session modes (see spec §4, "Cloudflare reality"):
 *  1. [importSession] — primary mode on rutracker.org since it sits behind a Cloudflare
 *     managed challenge: harvest the Cookie header (bb_session, cf_clearance, …) from a
 *     logged-in real browser, together with the User-Agent it was harvested with
 *     (cf_clearance is bound to the UA). Verified 2026-10-12: plain HTTP clients carrying
 *     these cookies pass Cloudflare and are seen as logged in.
 *  2. [login] — password login; works on mirrors without the Cloudflare challenge, kept
 *     for that case. rutracker.org's login.php itself is challenge-gated for non-browsers.
 *
 * Auth cookie note: rutracker now sets `bb_session` (the classic `bb_data` is accepted as
 * a legacy fallback when capturing from password login).
 */
class RutrackerClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) : TrackerClient, AutoCloseable {

    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    /** Full raw Cookie header value, e.g. "bb_session=…; cf_clearance=…; bb_guid=…". */
    @Volatile private var sessionCookie: String? = null

    /** cf_clearance is bound to the User-Agent the session was harvested with. */
    @Volatile private var userAgent: String = USER_AGENT

    /** Cloudflare-era session import: raw Cookie header from a logged-in browser + its User-Agent. */
    fun importSession(cookieHeader: String, harvestedUserAgent: String? = null) {
        require(cookieHeader.isNotBlank()) { "cookie header must not be blank" }
        sessionCookie = cookieHeader.trim()
        harvestedUserAgent?.takeIf { it.isNotBlank() }?.let { userAgent = it.trim() }
    }

    internal fun cookieHeader(): String =
        sessionCookie ?: throw TrackerError(
            "not logged in — import a browser session (cookies) or login",
            TrackerErrorKind.AUTH,
        )

    internal fun effectiveUserAgent(): String = userAgent

    override suspend fun login(username: String, password: String): Result<Unit> = runCatchingCtx {
        val resp = withContext(Dispatchers.IO) {
            http.post("$baseUrl/forum/login.php") {
                header("User-Agent", userAgent)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("login_username", username)
                    append("login_password", password)
                    append("login", "Вход")
                }))
            }
        }
        val body = resp.bodyAsText()
        if (body.contains("Just a moment", ignoreCase = true)) {
            throw TrackerError(
                "login page is Cloudflare-challenged; import a browser session instead (see README)",
                TrackerErrorKind.AUTH,
            )
        }
        val cookie = resp.headers.getAll("Set-Cookie")
            ?.firstNotNullOfOrNull { Regex("(?:bb_session|bb_data)=([^;]+)").find(it)?.groupValues?.get(1) }
        when {
            cookie != null -> sessionCookie = "bb_session=$cookie"
            body.contains("captcha", ignoreCase = true) ->
                throw TrackerError("login requires captcha; try again later or use a mirror", TrackerErrorKind.CAPTCHA)
            else -> throw TrackerError(parseLoginError(body) ?: "login failed (no session cookie)", TrackerErrorKind.AUTH)
        }
    }

    private fun parseLoginError(html: String): String? =
        Regex("<span class=\"error\"[^>]*>([^<]+)</span>").find(html)?.groupValues?.get(1)?.trim()

    override fun close() = http.close()

    override suspend fun search(query: SearchQuery): Result<SearchResultPage> = runCatchingCtx {
        require(query.text.isNotBlank()) { "query text must not be blank" }
        val resp = withContext(Dispatchers.IO) {
            http.get("$baseUrl/forum/tracker.php") {
                header("User-Agent", userAgent)
                header("Cookie", cookieHeader())
                url.parameters.append("nm", query.text)
                url.parameters.append("start", query.start.toString())
                query.forumId?.let { url.parameters.append("f[]", it.toString()) }
            }
        }
        val html = resp.bodyAsText()
        throwIfChallenged(html)
        throwIfRedirectedToLogin(resp.request.url.toString())
        SearchPageParser.parse(html, query.start)
    }

    override suspend fun torrentFile(topicId: Long): Result<ByteArray> = runCatchingCtx {
        val resp = withContext(Dispatchers.IO) {
            http.get("$baseUrl/forum/dl.php") {
                header("User-Agent", userAgent)
                header("Cookie", cookieHeader())
                url.parameters.append("t", topicId.toString())
            }
        }
        throwIfRedirectedToLogin(resp.request.url.toString())
        val bytes = resp.readBytes()
        if (bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()) bytes
        else {
            val text = String(bytes, Charsets.UTF_8)
            when {
                text.contains("captcha", ignoreCase = true) ->
                    throw TrackerError("download requires captcha; retry later", TrackerErrorKind.CAPTCHA)
                text.contains("Just a moment", ignoreCase = true) ->
                    throw TrackerError(
                        "Cloudflare-challenged; session cookies missing or expired — re-import from a logged-in browser",
                        TrackerErrorKind.AUTH,
                    )
                text.contains("frm-login") || text.contains("action=\"login.php\"") ->
                    throw TrackerError("session expired while fetching torrent", TrackerErrorKind.AUTH)
                else -> throw TrackerError("unexpected dl.php response", TrackerErrorKind.PARSE)
            }
        }
    }

    /** Cloudflare managed-challenge interstitial served instead of the real page. */
    private fun throwIfChallenged(html: String) {
        if (html.contains("Just a moment", ignoreCase = true) || html.contains("cf-chl", ignoreCase = true)) {
            throw TrackerError(
                "Cloudflare-challenged; session cookies missing or expired — re-import from a logged-in browser",
                TrackerErrorKind.AUTH,
            )
        }
    }

    /** rutracker redirects logged-out requests to login.php?redirect=… (Ktor follows it silently). */
    private fun throwIfRedirectedToLogin(finalUrl: String) {
        if (finalUrl.substringBefore('?').substringAfterLast('/').equals("login.php", ignoreCase = true)) {
            throw TrackerError("session expired (redirected to login)", TrackerErrorKind.AUTH)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://rutracker.org"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
    }
}

/** runCatching variant that does not catch CancellationException. */
private inline fun <T> runCatchingCtx(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
