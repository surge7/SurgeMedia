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
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RutrackerClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) : TrackerClient, AutoCloseable {

    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    @Volatile private var bbData: String? = null

    override suspend fun login(username: String, password: String): Result<Unit> = runCatchingCtx {
        val resp = withContext(Dispatchers.IO) {
            http.post("$baseUrl/forum/login.php") {
                header("User-Agent", USER_AGENT)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("login_username", username)
                    append("login_password", password)
                    append("login", "Вход")
                }))
            }
        }
        val body = resp.bodyAsText()
        val cookie = resp.headers.getAll("Set-Cookie")
            ?.firstNotNullOfOrNull { Regex("bb_data=([^;]+)").find(it)?.groupValues?.get(1) }
        when {
            cookie != null -> bbData = cookie
            body.contains("captcha", ignoreCase = true) ->
                throw TrackerError("login requires captcha; try again later or use a mirror", TrackerErrorKind.CAPTCHA)
            else -> throw TrackerError(parseLoginError(body) ?: "login failed (no bb_data cookie)", TrackerErrorKind.AUTH)
        }
    }

    internal fun cookieHeader(): String = "bb_data=" + (bbData ?: throw TrackerError("not logged in", TrackerErrorKind.AUTH))

    private fun parseLoginError(html: String): String? =
        Regex("<span class=\"error\"[^>]*>([^<]+)</span>").find(html)?.groupValues?.get(1)?.trim()

    override fun close() = http.close()

    private suspend fun <T> notImplementedYet(): T = throw NotImplementedError()
    override suspend fun search(query: SearchQuery): Result<SearchResultPage> = runCatching { notImplementedYet() }
    override suspend fun torrentFile(topicId: Long): Result<ByteArray> = runCatching { notImplementedYet() }

    companion object {
        const val DEFAULT_BASE_URL = "https://rutracker.org"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
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
