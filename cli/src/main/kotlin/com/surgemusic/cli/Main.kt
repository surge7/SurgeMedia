package com.surgemusic.cli

import com.surgemusic.core.Version
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SizeParser
import com.surgemusic.core.tracker.TrackerError
import com.surgemusic.core.tracker.jvm.RutrackerClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) = runBlocking {
    when (args.getOrNull(0)?.lowercase()) {
        "login" -> cmdLogin()
        // `runBlocking`'s lambda is not inline, so a bare non-local `return` is prohibited;
        // usage() returns Nothing, so the labelled return is well-typed and equivalent.
        "search" -> cmdSearch(args.getOrNull(1) ?: return@runBlocking usage("search requires a query"))
        "download" -> cmdDownload(args.drop(1))
        "version" -> println("SurgeMusic ${Version.APP}")
        else -> usage(null)
    }
}

private fun newClient(): RutrackerClient =
    RutrackerClient(baseUrl = System.getenv("RUTRACKER_BASE")?.takeIf { it.isNotBlank() } ?: RutrackerClient.DEFAULT_BASE_URL)

private fun requireCredentials(): Credentials =
    loadCredentials() ?: run {
        System.err.println(
            "No credentials. Either set RUTRACKER_USER/RUTRACKER_PASS or create\n" +
                "~/.config/surgemusic/credentials with username on line 1, password on line 2 (chmod 600)."
        )
        exitProcess(2)
    }

private suspend fun cmdLogin() {
    val creds = requireCredentials()
    newClient().use { client ->
        client.login(creds.username, creds.password)
            .onSuccess { println("login OK") }
            .onFailure { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
    }
}

private suspend fun cmdSearch(query: String) {
    val creds = requireCredentials()
    newClient().use { client ->
        client.login(creds.username, creds.password).getOrElse { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
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
    val creds = requireCredentials()

    newClient().use { client ->
        client.login(creds.username, creds.password).getOrElse { e -> println("login FAILED: ${describe(e)}"); exitProcess(1) }
        val torrentBytes = client.torrentFile(topicId).getOrElse { e -> println("torrent fetch FAILED: ${describe(e)}"); exitProcess(1) }
        println("torrent fetched (${torrentBytes.size} bytes) -> $outDir")
        // `JLibTorrentEngine` implements only TorrentEngine (whose close() is its own member),
        // not AutoCloseable, so stdlib `use {}` does not resolve; try/finally is equivalent.
        val engine = JLibTorrentEngine()
        try {
            engine.download(torrentBytes, outDir).collect { event ->
                when (event) {
                    is EngineEvent.Progress -> print(
                        "\r${SizeParser.formatBytes(event.bytesDone)} / ${SizeParser.formatBytes(event.bytesTotal)}  " +
                            "${SizeParser.formatBytes(event.downloadSpeed)}/s  peers=${event.peers}   "
                    )
                    is EngineEvent.Completed -> println("\ndownload complete: $outDir")
                    is EngineEvent.Failed -> { println("\ndownload FAILED: ${event.message}"); exitProcess(1) }
                }
            }
        } finally {
            engine.close()
        }
    }
}

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
        SurgeMusic ${Version.APP}
        usage:
          surgemusic login
          surgemusic search <query>
          surgemusic download <topicId> [--out DIR]
        env: RUTRACKER_USER, RUTRACKER_PASS, RUTRACKER_BASE
        credentials file: ~/.config/surgemusic/credentials (user\npass, chmod 600)
        """.trimIndent()
    )
    exitProcess(if (error == null) 0 else 2)
}
