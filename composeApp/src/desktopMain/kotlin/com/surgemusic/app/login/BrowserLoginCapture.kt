package com.surgemusic.app.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Desktop browser sign-in — the click-once twin of androidMain's WebViewLoginScreen.
 *
 * rutracker sits behind Cloudflare, so the app can't POST credentials itself. Instead
 * [capture] launches the user's real Chromium browser (Chrome / Edge / Yandex — see
 * [findChromiumBrowser]) on the tracker's login page in a throwaway profile with the
 * DevTools remote-debugging port open. The user signs in inside that ordinary browser
 * window (Cloudflare passes because it IS a real browser); meanwhile we poll the page
 * session's cookie jar over the DevTools websocket. As soon as the jar holds the login
 * cookie (`bb_session`) — waiting a few extra seconds for `cf_clearance` when the
 * challenge is still running — we assemble the raw Cookie header for the tracker host
 * and pair it with the browser's own User-Agent (read from `/json/version`), which is
 * exactly the pairing Cloudflare requires (cf_clearance is UA-bound). The browser is
 * then closed and the temp profile deleted; the caller persists + validates the
 * [CapturedSession] through the normal session store.
 *
 * Why a separate profile: Chrome only accepts `--remote-debugging-port` with a
 * non-default `--user-data-dir`, the user's normal profile is never touched, and
 * everything the login leaves behind is deleted afterwards. Safari and Firefox expose
 * no such debug protocol — for those the manual Cookie/User-Agent import remains.
 */
object BrowserLoginCapture {

    /** A session captured from the browser — same shape as the manual import fields. */
    data class CapturedSession(val cookieHeader: String, val userAgent: String)

    sealed interface Result {
        data class Success(val session: CapturedSession) : Result
        data object BrowserClosed : Result
        data class Error(val message: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * First Chromium-based browser found, in preference order (Chrome, Edge, Yandex,
     * Brave, Chromium, Opera). `$CHROME_BIN` wins when set (handy on CI). Null when no
     * supported browser is installed — the UI then falls back to manual import.
     */
    fun findChromiumBrowser(): Path? {
        System.getenv("CHROME_BIN")?.takeIf { Files.isExecutable(Path.of(it)) }?.let { return Path.of(it) }
        val home = System.getProperty("user.home")
        val candidates = sequence {
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                yield("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
                yield("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge")
                yield("/Applications/Yandex.app/Contents/MacOS/Yandex")
                yield("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser")
                yield("/Applications/Chromium.app/Contents/MacOS/Chromium")
                yield("/Applications/Opera.app/Contents/MacOS/Opera")
                yield("$home/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
            } else {
                yield("/usr/bin/google-chrome-stable")
                yield("/usr/bin/google-chrome")
                yield("/usr/bin/chromium-browser")
                yield("/usr/bin/chromium")
                yield("/snap/bin/chromium")
                yield("/usr/bin/yandex-browser")
            }
        }
        return candidates.map(Path::of).firstOrNull { Files.isExecutable(it) }
    }

    /**
     * Launches [browser] on [loginUrl] and polls its cookies until [requiredCookie]
     * (default `bb_session`) appears for the login URL's host. Once seen, waits up to
     * [optionalGrace] for [optionalCookie] (`cf_clearance`) too — capture proceeds
     * without it after the grace window, validation decides if it was needed.
     * [extraArgs] lets tests run `--headless=new`; the real flow opens a visible window.
     */
    suspend fun capture(
        browser: Path,
        loginUrl: String,
        requiredCookie: String = "bb_session",
        optionalCookie: String = "cf_clearance",
        extraArgs: List<String> = emptyList(),
        timeout: Duration = Duration.ofMinutes(5),
        optionalGrace: Duration = Duration.ofSeconds(8),
        onStatus: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val host = runCatching { URI(loginUrl).host }.getOrNull()
            ?: return@withContext Result.Error("cannot parse host from $loginUrl")
        val port = freePort()
        val profile = Files.createTempDirectory("surgemusic-login")
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val args = buildList {
            add(browser.toString())
            add("--remote-debugging-port=$port")
            add("--user-data-dir=$profile")
            add("--no-first-run")
            add("--no-default-browser-check")
            add("--window-size=1100,800")
            addAll(extraArgs)
            add(loginUrl)
        }
        onStatus("launching")
        val process = try {
            ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            deleteQuietly(profile)
            return@withContext Result.Error(e.message ?: "failed to launch ${browser.fileName}")
        }

        try {
            val userAgent = awaitDevtoolsUserAgent(client, port, Duration.ofSeconds(25))
                ?: return@withContext Result.Error("browser did not open a debug port (crashed?)")
            onStatus("waiting for login")
            val deadline = System.nanoTime() + timeout.toNanos()
            var requiredSeenAt = 0L
            while (System.nanoTime() < deadline) {
                coroutineContext.ensureActive()
                if (!process.isAlive) return@withContext Result.BrowserClosed
                val cookies = pageWebSocketUrl(client, port)?.let { fetchCookies(client, it) }.orEmpty()
                val header = assembleCookieHeader(cookies, host)
                val hasRequired = cookies.any { it.name == requiredCookie && domainMatches(it.domain, host) }
                val hasOptional = optionalCookie.isBlank() ||
                    cookies.any { it.name == optionalCookie && domainMatches(it.domain, host) }
                if (hasRequired) {
                    if (requiredSeenAt == 0L) requiredSeenAt = System.nanoTime()
                    if (hasOptional || System.nanoTime() - requiredSeenAt >= optionalGrace.toNanos()) {
                        onStatus("captured")
                        return@withContext Result.Success(CapturedSession(header, userAgent))
                    }
                }
                delay(1_500)
            }
            Result.Error("timed out waiting for $requiredCookie cookie")
        } finally {
            runCatching { process.destroy() }
            val killDeadline = System.nanoTime() + 2_000_000_000L
            while (process.isAlive && System.nanoTime() < killDeadline) {
                runCatching { delay(100) }
            }
            if (process.isAlive) process.destroyForcibly()
            client.dispatcher.executorService.shutdown()
            deleteQuietly(profile)
        }
    }

    /** Chrome only allows the debug port on a non-default profile — poll `/json/version` for it. */
    private suspend fun awaitDevtoolsUserAgent(client: OkHttpClient, port: Int, timeout: Duration): String? {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val body = httpGet(client, "http://127.0.0.1:$port/json/version")
            val ua = if (body != null) {
                runCatching {
                    json.parseToJsonElement(body).jsonObject["User-Agent"]?.jsonPrimitive?.content
                }.getOrNull()
            } else {
                null
            }
            if (!ua.isNullOrBlank()) return ua
            delay(300)
        }
        return null
    }

    /** First real page target's websocket URL from `/json/list`, or null while Chrome is still starting. */
    private fun pageWebSocketUrl(client: OkHttpClient, port: Int): String? {
        val body = httpGet(client, "http://127.0.0.1:$port/json/list") ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonArray
                .mapNotNull { it.jsonObject }
                .firstOrNull { (it["type"]?.jsonPrimitive?.content ?: "") == "page" && !(it["url"]?.jsonPrimitive?.content ?: "").startsWith("devtools://") }
                ?.get("webSocketDebuggerUrl")?.jsonPrimitive?.content
        }.getOrNull()
    }

    /**
     * One websocket per poll (simple, and CDP tolerates it): enable Network, then ask
     * both `Network.getAllCookies` and `Storage.getCookies` — the latter for newer
     * builds that retire the former — and take whichever answers with a cookie array.
     */
    private fun fetchCookies(client: OkHttpClient, pageWebSocketUrl: String): List<CdpCookie>? {
        val done = CompletableFuture<List<CdpCookie>>()
        val socket = client.newWebSocket(
            Request.Builder().url(pageWebSocketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"id":1,"method":"Network.enable"}""")
                    webSocket.send("""{"id":2,"method":"Network.getAllCookies"}""")
                    webSocket.send("""{"id":3,"method":"Storage.getCookies"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseCookieResult(text)?.let { cookies ->
                        done.complete(cookies)
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.completeExceptionally(t)
                }
            },
        )
        return try {
            done.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        } finally {
            socket.cancel()
        }
    }

    /** `{"id":…,"result":{"cookies":[…]}}` → cookies, or null for events/error replies. */
    internal fun parseCookieResult(message: String): List<CdpCookie>? {
        val cookies = runCatching {
            json.parseToJsonElement(message).jsonObject["result"]?.jsonObject?.get("cookies")?.jsonArray
        }.getOrNull() ?: return null
        return cookies.mapNotNull { entry ->
            runCatching {
                val obj = entry.jsonObject
                CdpCookie(
                    name = obj["name"]!!.jsonPrimitive.content,
                    value = obj["value"]!!.jsonPrimitive.content,
                    domain = obj["domain"]?.jsonPrimitive?.content ?: "",
                )
            }.getOrNull()
        }
    }

    private fun httpGet(client: OkHttpClient, url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Temp profiles are deleted best-effort — Chrome flushes files asynchronously at exit. */
    private suspend fun deleteQuietly(dir: Path) {
        var attempts = 0
        while (Files.exists(dir) && attempts < 4) {
            runCatching { dir.toFile().deleteRecursively() }
            if (!Files.exists(dir)) return
            runCatching { delay(400) }
            attempts++
        }
    }
}

/** Raw CDP cookie fields — only what the header assembly needs. */
internal data class CdpCookie(val name: String, val value: String, val domain: String)

/** Cookie domains arrive as `rutracker.org` or `.rutracker.org`; both must match the host and its subdomains. */
internal fun domainMatches(cookieDomain: String, host: String): Boolean {
    val domain = cookieDomain.removePrefix(".").lowercase()
    val target = host.lowercase()
    if (domain.isEmpty()) return false
    return target == domain || target.endsWith(".$domain")
}

/** Builds the raw Cookie header exactly like a browser would send it for [host]. */
internal fun assembleCookieHeader(cookies: List<CdpCookie>, host: String): String =
    cookies.filter { domainMatches(it.domain, host) }.joinToString("; ") { "${it.name}=${it.value}" }
