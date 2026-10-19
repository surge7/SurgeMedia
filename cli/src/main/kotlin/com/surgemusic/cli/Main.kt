package com.surgemusic.cli

import com.surgemusic.core.Version
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.jvm.RutrackerClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    when (args.getOrNull(0)?.lowercase()) {
        "login" -> cmdLogin()
        "session" -> cmdSession()
        // `runBlocking`'s lambda is not inline, so a bare non-local `return` is prohibited;
        // usage() returns Nothing, so the labelled return is well-typed and equivalent.
        "search" -> cmdSearch(args.getOrNull(1) ?: return@runBlocking usage("search requires a query"))
        "download" -> cmdDownload(args.drop(1))
        "version" -> println("Surge Media ${Version.APP}")
        else -> usage(null)
    }
}

private fun newClient(): RutrackerClient =
    RutrackerClient(baseUrl = System.getenv("RUTRACKER_BASE")?.takeIf { it.isNotBlank() } ?: RutrackerClient.DEFAULT_BASE_URL)

/**
 * Establishes a tracker session: an imported browser session (Cloudflare-era primary
 * mode on rutracker.org) when one is configured, otherwise password login.
 */
private suspend fun openSession(client: RutrackerClient) {
    val session = loadSessionConfig()
    if (session != null) {
        client.importSession(cookieHeader = session.cookieHeader, harvestedUserAgent = session.userAgent)
        return
    }
    val creds = loadCredentials() ?: run {
        System.err.println(
            "No session configured. rutracker.org sits behind a Cloudflare browser check, so the\n" +
                "primary mode is an imported browser session:\n" +
                "  1. log into rutracker.org in your browser\n" +
                "  2. copy the raw Cookie request header (devtools → Network → any forum request)\n" +
                "  3. write it to ~/.config/surgemusic/cookies (line 1: cookies, line 2: your browser's User-Agent), chmod 600\n" +
                "Alternatively, for mirrors without the Cloudflare check: RUTRACKER_USER/RUTRACKER_PASS\n" +
                "or ~/.config/surgemusic/credentials (user line 1, password line 2)."
        )
        exitProcess(2)
    }
    client.login(creds.username, creds.password).getOrElse { e ->
        println("login FAILED: ${describe(e)}")
        exitProcess(1)
    }
}

private suspend fun cmdLogin() {
    val creds = loadCredentials() ?: run {
        System.err.println("No credentials. Set RUTRACKER_USER/RUTRACKER_PASS or ~/.config/surgemusic/credentials (user line 1, pass line 2).")
        exitProcess(2)
    }
    newClient().use { client ->
        client.login(creds.username, creds.password)
            .onSuccess { println("login OK") }
            .onFailure { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
    }
}

/** Validates the imported browser session with a live search round-trip. */
private suspend fun cmdSession() {
    val session = loadSessionConfig() ?: run {
        System.err.println("No imported session. Put the raw Cookie header from a logged-in browser into ~/.config/surgemusic/cookies")
        exitProcess(2)
    }
    newClient().use { client ->
        client.importSession(cookieHeader = session.cookieHeader, harvestedUserAgent = session.userAgent)
        client.search(SearchQuery(text = "test")).fold(
            onSuccess = { page -> println("session OK — live search returned ${page.rows.size} results") },
            onFailure = { e -> println("session FAILED: ${describe(e)}"); exitProcess(1) },
        )
    }
}

private suspend fun cmdSearch(query: String) {
    newClient().use { client ->
        openSession(client)
        // `Result.fold`'s lambdas are not inline, so a bare non-local `return` is prohibited;
        // expressed as if/else with identical behavior.
        client.search(SearchQuery(text = query)).fold(
            onSuccess = { page ->
                if (page.rows.isEmpty()) {
                    println("no results for \"$query\"")
                } else {
                    page.rows.forEach { r ->
                        println("[${r.topicId}] ${r.title}")
                        println("      ${r.forumName} · ${SizeParser.formatBytes(r.sizeBytes)} · \u2191${r.seeds} \u2193${r.leechs} · ${r.dateText}")
                    }
                    println("\ndownload with: surgemusic download <topicId>")
                }
            },
            onFailure = { e -> println("search FAILED: ${describe(e)}"); exitProcess(1) },
        )
    }
}

private suspend fun cmdDownload(args: List<String>) {
    val topicId = args.getOrNull(0)?.toLongOrNull() ?: return usage("download requires a numeric topicId")
    val outDir = args.drop(1).option("--out") ?: Path.of(System.getProperty("user.home"), "Music", "SurgeMusic").toString()

    newClient().use { client ->
        openSession(client)
        val torrentBytes = client.torrentFile(topicId).getOrElse { e -> println("torrent fetch FAILED: ${describe(e)}"); exitProcess(1) }
        println("torrent fetched (${torrentBytes.size} bytes) -> $outDir")
        val engine = JLibTorrentEngine()
        val ok = try {
            val id = "cli-$topicId"
            // events is a SharedFlow with replay=0: events emitted while no collector is
            // attached are dropped. So launch the collector FIRST (it completes `done` on
            // the terminal event for `id`), then start, then await. `coroutineScope {}`
            // cancels the collector when the block exits, however it exits.
            val done = CompletableDeferred<Boolean>()
            val result = coroutineScope {
                val job = launch {
                    engine.events.collect { ev ->
                        if (ev.id != id) return@collect
                        when (ev) {
                            is EngineEvent.Added -> Unit
                            is EngineEvent.Progress -> printProgress(ev)
                            is EngineEvent.Completed -> { println("\ndownload complete: $outDir"); done.complete(true) }
                            is EngineEvent.Failed -> { println("\ndownload FAILED: ${ev.message}"); done.complete(false) }
                        }
                    }
                }
                engine.start(id, torrentBytes, outDir)
                done.await().also { job.cancel() }
            }
            result
        } finally {
            engine.close()
        }
        if (!ok) exitProcess(1)
    }
}

/** The sealed interface itself has no `id` member; every variant does. */
private val EngineEvent.id: String
    get() = when (this) {
        is EngineEvent.Added -> id
        is EngineEvent.Progress -> id
        is EngineEvent.Completed -> id
        is EngineEvent.Failed -> id
    }

private fun printProgress(ev: EngineEvent.Progress) = print(
    "\r${SizeParser.formatBytes(ev.bytesDone)} / ${SizeParser.formatBytes(ev.bytesTotal)}  " +
        "${SizeParser.formatBytes(ev.downloadSpeed)}/s  peers=${ev.peers}   "
)

private fun List<String>.option(name: String): String? {
    val i = indexOf(name)
    return if (i >= 0 && i + 1 < size) this[i + 1] else null
}

private fun describe(e: Throwable): String =
    if (e is TrackerError) "${e.kind.name}: ${e.message}" + (e.selector?.let { " (selector=$it)" } ?: "") else e.message ?: e.toString()

private fun usage(error: String?): Nothing {
    error?.let { System.err.println("error: $it") }
    println(
        """
        Surge Media ${Version.APP}
        usage:
          surgemusic session                  validate the imported browser session
          surgemusic login                    password login (mirrors without Cloudflare)
          surgemusic search <query>
          surgemusic download <topicId> [--out DIR]
        env: RUTRACKER_COOKIES (+ RUTRACKER_UA), RUTRACKER_USER/RUTRACKER_PASS, RUTRACKER_BASE
        session file: ~/.config/surgemusic/cookies  (line 1: raw Cookie header from a logged-in
                     browser; line 2: that browser's User-Agent; chmod 600)
        credentials file: ~/.config/surgemusic/credentials (user\npass, chmod 600)
        """.trimIndent()
    )
    exitProcess(if (error == null) 0 else 2)
}
