package com.surgemusic.app.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * [findChromiumBrowser]) on the tracker's login page in a dedicated login profile with
 * the DevTools remote-debugging port open. The user signs in inside that ordinary
 * browser window (Cloudflare passes because it IS a real browser); meanwhile we poll
 * the page session's cookie jar over the DevTools websocket — one socket kept for the
 * whole capture, standalone cookie getters only, because Cloudflare fingerprints CDP
 * attach churn and `Network.enable` (see [CookieSocket]). As soon as the jar holds the
 * login cookie (`bb_session`) — waiting a few extra seconds for `cf_clearance` when the
 * challenge is still running — we assemble the raw Cookie header for the tracker host
 * and pair it with the browser's own User-Agent (read from `/json/version`), which is
 * exactly the pairing Cloudflare requires (cf_clearance is UA-bound). The browser is
 * then closed; the profile is kept so challenge state (`cf_clearance`, `cf_chl*`)
 * survives between attempts — Cloudflare passes more easily on retry and on the next
 * login. The caller persists + validates the [CapturedSession] through the normal
 * session store.
 *
 * One launch-arg subtlety: a DevTools port alone makes Chromium flag itself as automated
 * (`navigator.webdriver === true`), which Cloudflare then challenges forever — so the
 * launch also carries `--disable-blink-features=AutomationControlled` (see
 * [browserLaunchArgs]).
 *
 * Why a separate profile: Chrome only accepts `--remote-debugging-port` with a
 * non-default `--user-data-dir`, and the user's normal profile is never touched. The
 * login profile lives under `~/.surgemusic` ([defaultLoginProfile]), next to the
 * session store — no new privacy surface. Tests that want the old delete-after behavior
 * pass a temp dir plus `deleteProfile = true`. Safari and Firefox expose no such debug
 * protocol — for those the manual Cookie/User-Agent import remains.
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

    /** Continuous challenge-title duration after which [capture] reports a loop via [onChallengeLoop]. */
    private val challengeLoopAfter: Duration = Duration.ofSeconds(60)

    /**
     * First Chromium-based browser found, in preference order (Chrome, Edge, Yandex,
     * Brave, Chromium, Opera). `$CHROME_BIN` wins when set (handy on CI). Null when no
     * supported browser is installed — the UI then falls back to manual import.
     *
     * Windows looks in `Program Files`, `Program Files (x86)` and `%LOCALAPPDATA%`
     * (Chromium browsers install per-user by default, so the last one is the common
     * location for Yandex/Brave/Chromium/Opera). Edge ships preinstalled on Windows 11,
     * so browser sign-in works out of the box there.
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
            } else if (System.getProperty("os.name").startsWith("Windows")) {
                val pf = System.getenv("ProgramFiles") ?: "C:\\Program Files"
                val pf86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
                val lad = System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
                // Chrome: machine-wide (64-bit in Program Files, 32-bit in (x86)) or per-user.
                yield("$pf\\Google\\Chrome\\Application\\chrome.exe")
                yield("$pf86\\Google\\Chrome\\Application\\chrome.exe")
                yield("$lad\\Google\\Chrome\\Application\\chrome.exe")
                // Edge: ships preinstalled on Windows 11; the classic install root is (x86).
                yield("$pf86\\Microsoft\\Edge\\Application\\msedge.exe")
                yield("$pf\\Microsoft\\Edge\\Application\\msedge.exe")
                yield("$lad\\Microsoft\\Edge\\Application\\msedge.exe")
                yield("$lad\\Yandex\\YandexBrowser\\Application\\browser.exe")
                yield("$pf\\Yandex\\YandexBrowser\\Application\\browser.exe")
                yield("$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe")
                yield("$lad\\BraveSoftware\\Brave-Browser\\Application\\brave.exe")
                yield("$lad\\Chromium\\Application\\chrome.exe")
                yield("$lad\\Programs\\Opera\\opera.exe")
                yield("$pf\\Opera\\opera.exe")
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
     *
     * Profile: [profileDir] (tests pass a temp dir there) or, by default, the persistent
     * [defaultLoginProfile] — kept after the capture, because challenge state
     * (`cf_clearance`, `cf_chl*`) surviving between attempts is what makes Cloudflare
     * pass on retry and on the next login; `deleteProfile = true` restores the old
     * delete-on-exit behavior for throwaway dirs. [onChallengeLoop] fires once when the
     * page has sat on a Cloudflare interstitial ([isChallengeTitle]) continuously for
     * over [challengeLoopAfter] — a UI hint, never an abort: the user may still get
     * through.
     *
     * Debug port: the browser starts with `--remote-debugging-port=0` — Chrome binds a
     * free port itself (no `freePort()` bind race) and writes the port it picked to
     * `<profileDir>/DevToolsActivePort` (first line). We poll that file for a while;
     * browsers that never write it fall back to the old fixed-port launch.
     */
    suspend fun capture(
        browser: Path,
        loginUrl: String,
        requiredCookie: String = "bb_session",
        optionalCookie: String = "cf_clearance",
        extraArgs: List<String> = emptyList(),
        timeout: Duration = Duration.ofMinutes(5),
        optionalGrace: Duration = Duration.ofSeconds(8),
        profileDir: Path? = null,
        deleteProfile: Boolean = false,
        onStatus: (String) -> Unit = {},
        onChallengeLoop: () -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val host = runCatching { URI(loginUrl).host }.getOrNull()
            ?: return@withContext Result.Error("cannot parse host from $loginUrl")
        val profile = (profileDir ?: defaultLoginProfile()).also { Files.createDirectories(it) }
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val cookieSocket = CookieSocket(client)
        var process: Process? = null
        try {
            onStatus("launching")
            var current = launchBrowser(browser, loginUrl, profile, extraArgs, debugPort = 0)
            process = current
            val port = awaitDevtoolsPort(profile, Duration.ofSeconds(15)) ?: run {
                // Browser never wrote DevToolsActivePort — relaunch the old way, on a fixed port.
                stopProcess(current)
                val fixedPort = freePort()
                current = launchBrowser(browser, loginUrl, profile, extraArgs, debugPort = fixedPort)
                process = current
                fixedPort
            }
            val userAgent = awaitDevtoolsUserAgent(client, port, Duration.ofSeconds(25))
                ?: return@withContext Result.Error("browser did not open a debug port (crashed?)")
            onStatus("waiting for login")
            val deadline = System.nanoTime() + timeout.toNanos()
            var requiredSeenAt = 0L
            var challengeSince = 0L
            var challengeLoopReported = false
            while (System.nanoTime() < deadline) {
                coroutineContext.ensureActive()
                if (!current.isAlive) return@withContext Result.BrowserClosed
                val target = pageTarget(client, port)
                val cookies = target?.let { cookieSocket.fetch(it.webSocketDebuggerUrl) }.orEmpty()
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
                // Challenge-loop detection: while the page title stays on a Cloudflare
                // interstitial, remember when that streak began; after a full minute of
                // the challenge not resolving, hint the UI once. Deliberately non-fatal —
                // the user may still tick through. A non-challenge title resets the streak.
                val now = System.nanoTime()
                if (target != null && isChallengeTitle(target.title)) {
                    if (challengeSince == 0L) challengeSince = now
                    if (!challengeLoopReported && now - challengeSince >= challengeLoopAfter.toNanos()) {
                        challengeLoopReported = true
                        onChallengeLoop()
                    }
                } else {
                    challengeSince = 0L
                }
                delay(1_500)
            }
            Result.Error("timed out waiting for $requiredCookie cookie")
        } catch (e: IOException) {
            Result.Error(e.message ?: "failed to launch ${browser.fileName}")
        } finally {
            // NonCancellable: on CANCEL the coroutine is already cancelled when the
            // finally runs, so the delays below would throw on the spot — skipping the
            // graceful-kill wait and (for throwaway profiles) the cookie-bearing deletion.
            withContext(NonCancellable) {
                cookieSocket.close()
                process?.let { stopProcess(it) }
                client.dispatcher.executorService.shutdown()
                if (deleteProfile) deleteQuietly(profile)
            }
        }
    }

    /**
     * Launch args for the capture browser: binary first, DevTools port, login profile,
     * the fixed window flags, [extraArgs] (tests add `--headless=new` there), [loginUrl]
     * last. Internal so tests can pin the exact args both capture paths (port-0 launch
     * and fixed-port fallback relaunch) start the browser with.
     *
     * Why `--disable-blink-features=AutomationControlled`, applied unconditionally (not
     * only once a challenge shows up): launching with `--remote-debugging-port` makes
     * Chromium enable its AutomationControlled runtime feature, so pages see
     * `navigator.webdriver === true`. Measured on the real site, same fresh profile,
     * same URL: the port alone → `webdriver = true` and the "Just a moment..."
     * interstitial; with this flag → `webdriver = false` and the real page, no
     * challenge. Cloudflare's bot management reads that signal and re-challenges
     * automation-flagged clients forever — even after the user ticks "Verify you are
     * human" — so suppressing it restores a clean browser identity. The DevTools port
     * itself stays open: the cookie polling over the single CDP socket ([CookieSocket])
     * depends on it, and the flag doesn't disturb the CDP connection, so the harvest
     * keeps working as before.
     */
    internal fun browserLaunchArgs(
        browser: Path,
        loginUrl: String,
        profile: Path,
        extraArgs: List<String>,
        debugPort: Int,
    ): List<String> = buildList {
        add(browser.toString())
        add("--remote-debugging-port=$debugPort")
        add("--user-data-dir=$profile")
        add("--disable-blink-features=AutomationControlled")
        add("--no-first-run")
        add("--no-default-browser-check")
        add("--window-size=1100,800")
        addAll(extraArgs)
        add(loginUrl)
    }

    /** Starts [browser] on [loginUrl] in the login [profile]; only IOException escapes. */
    private fun launchBrowser(
        browser: Path,
        loginUrl: String,
        profile: Path,
        extraArgs: List<String>,
        debugPort: Int,
    ): Process = ProcessBuilder(browserLaunchArgs(browser, loginUrl, profile, extraArgs, debugPort))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectErrorStream(true)
        .start()

    /**
     * Polls `<profile>/DevToolsActivePort` — written by Chrome once the debug port is
     * bound; its first line is the port actually picked (second is the browser websocket
     * path, unused). Null when no usable port shows up within [timeout].
     */
    private suspend fun awaitDevtoolsPort(profile: Path, timeout: Duration): Int? {
        val portFile = profile.resolve("DevToolsActivePort")
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val port = runCatching {
                Files.newBufferedReader(portFile).useLines { lines -> lines.firstOrNull() }
            }.getOrNull()?.trim()?.toIntOrNull()
            if (port != null && port > 0) return port
            delay(200)
        }
        return null
    }

    /** destroy(), up to ~2s of waiting, then destroyForcibly — shared by cleanup and the fallback relaunch. */
    private suspend fun stopProcess(process: Process) {
        runCatching { process.destroy() }
        val killDeadline = System.nanoTime() + 2_000_000_000L
        while (process.isAlive && System.nanoTime() < killDeadline) {
            delay(100)
        }
        if (process.isAlive) process.destroyForcibly()
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

    /** First real page target from `/json/list` — its websocket URL and window title — or null while Chrome is still starting. */
    private fun pageTarget(client: OkHttpClient, port: Int): PageTarget? {
        val body = httpGet(client, "http://127.0.0.1:$port/json/list") ?: return null
        return runCatching {
            json.parseToJsonElement(body).jsonArray
                .mapNotNull { it.jsonObject }
                .firstOrNull { (it["type"]?.jsonPrimitive?.content ?: "") == "page" && !(it["url"]?.jsonPrimitive?.content ?: "").startsWith("devtools://") }
                ?.let { entry ->
                    val wsUrl = entry["webSocketDebuggerUrl"]?.jsonPrimitive?.content ?: return@let null
                    PageTarget(webSocketDebuggerUrl = wsUrl, title = entry["title"]?.jsonPrimitive?.content ?: "")
                }
        }.getOrNull()
    }

    /** A real page target from `/json/list`: its DevTools websocket and window title. */
    private data class PageTarget(val webSocketDebuggerUrl: String, val title: String)

    /**
     * The page-target DevTools websocket, kept open for the whole capture instead of a
     * fresh attach per poll. Cloudflare's bot management fingerprints CDP attachment:
     * opening a new websocket every 1.5 s poll — each also sending `Network.enable` on
     * the challenge page itself — is what put the "Verify you are human" widget into an
     * endless loop. So [fetch] sends only the two standalone cookie getters with
     * incrementing ids — `Network.getAllCookies` (older builds) + `Storage.getCookies`
     * (newer builds that retire the former), whichever answers first wins; neither
     * requires `Network.enable`, so none is ever sent — and a reconnect happens only
     * when the page navigated ([pageTarget]'s `webSocketDebuggerUrl` changed) or the
     * socket died.
     */
    private class CookieSocket(private val client: OkHttpClient) {
        @Volatile private var socket: WebSocket? = null
        private var attachedTo: String? = null
        private var nextRequestId = 1

        @Volatile private var pending: CompletableFuture<List<CdpCookie>>? = null

        /** Cookie jar from the page at [url], or null while no answer arrives within 5 s. */
        fun fetch(url: String): List<CdpCookie>? {
            val done = CompletableFuture<List<CdpCookie>>()
            val attached = socket
            if (attached == null || attachedTo != url) {
                // (Re)attach: a new page target after navigation, or the old socket died.
                attached?.cancel()
                attachedTo = url
                pending = done
                socket = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sendGetters(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (webSocket === socket) parseCookieResult(text)?.let { pending?.complete(it) }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            // Stale callbacks from a just-replaced socket must not kill the new one.
                            if (webSocket !== socket) return
                            pending?.completeExceptionally(t)
                            socket = null
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (webSocket === socket) socket = null
                        }
                    },
                )
            } else {
                pending = done
                if (!sendGetters(attached)) {
                    // Send refused — the socket died silently; reconnect on the next poll.
                    attached.cancel()
                    socket = null
                    return null
                }
            }
            return try {
                done.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                null
            }
        }

        /** Both standalone getters, ids incrementing so replies never collide. */
        private fun sendGetters(webSocket: WebSocket): Boolean {
            val getAllCookiesId = nextRequestId++
            val storageId = nextRequestId++
            val first = webSocket.send("""{"id":$getAllCookiesId,"method":"Network.getAllCookies"}""")
            webSocket.send("""{"id":$storageId,"method":"Storage.getCookies"}""")
            return first
        }

        /** Detaches — capture cleanup. */
        fun close() {
            socket?.cancel()
            socket = null
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

/**
 * Default persistent login profile: `~/.surgemusic/login-profile` — under the user
 * home, next to the session store. Kept between attempts (unlike the old per-attempt
 * temp dir) so Cloudflare challenge state survives retries; internal for tests.
 */
internal fun defaultLoginProfile(): Path =
    Path.of(System.getProperty("user.home"), ".surgemusic", "login-profile")

/**
 * True when a page title looks like a Cloudflare interstitial: "Just a moment…",
 * "Attention Required!", Russian «Проверка браузера…». Prefix-only on purpose —
 * conservative, so an ordinary page title never reads as a challenge.
 */
internal fun isChallengeTitle(title: String): Boolean {
    val trimmed = title.trim()
    return trimmed.startsWith("Just a moment") ||
        trimmed.startsWith("Attention Required") ||
        trimmed.startsWith("Проверка браузера")
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
