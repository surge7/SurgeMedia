package com.surgemusic.app.tracker

import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadRequest
import com.surgemusic.core.downloads.PreparedTorrent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.TrackerClient
import com.surgemusic.core.tracker.jvm.RutrackerClient

/**
 * The AppContainer members that were near-verbatim on desktop and android (the appShared
 * source set compiles into both target compilations): the rutracker client singleton
 * ([trackerClient]), the search→download pipeline steps ([prepare]/[startDownload]) and
 * the Settings validate probe ([validateSession]). The platform seams are parameters:
 * where the mirror base URL comes from and where the session lives ([trackerClient]),
 * the per-topic staging root and what happens right after an enqueue ([startDownload] —
 * android starts its quota-gated foreground service there, desktop passes the default
 * no-op). State lives on this process-wide object: only one AppContainer is actualized
 * per platform, so the singleton client behavior is unchanged.
 */
object TrackerAppFactory {

    private val clientLock = Any()

    @Volatile private var client: TrackerClient? = null

    /** Base URL the current [client] was built with; a mirror edit (Settings, Task 9) rebuilds it. */
    @Volatile private var clientMirror: String? = null

    /**
     * Tracker client singleton (the [TrackerClient] seam — rutracker is just the current
     * implementation, constructed here as the one concrete spot) — same mirror-rebuild +
     * session re-import pattern on both platforms. [base]: the mirror setting resolved by
     * the caller (blank falls back to the rutracker default at the settings seam).
     * Rebuilt whenever [base] differs from the current base URL — the client's
     * constructor is the only mirror seam it exposes. The old client is closed first
     * (it holds an OkHttp-backed Ktor engine). [session]: re-imported into the returned
     * client on every call (idempotent — two volatile field writes) so a search right
     * after a paste-cookie / WebView login carries the fresh cookies.
     */
    fun trackerClient(base: String, session: () -> Pair<String, String>?): TrackerClient {
        val active: TrackerClient
        synchronized(clientLock) {
            var current = client
            if (current == null || clientMirror != base) {
                current?.close()
                current = RutrackerClient(baseUrl = base)
                client = current
                clientMirror = base
            }
            active = current!!
        }
        session()?.let { (cookieHeader, userAgent) -> active.importSession(cookieHeader, userAgent) }
        return active
    }

    /**
     * Search→download, step 1 (plan Task 8, split for the file chooser): fetch the
     * .torrent for [row] through [client] and parse its file list with [engine]. A parse
     * failure degrades to an empty list — the chooser is skipped and everything downloads.
     */
    suspend fun prepare(
        row: SearchResultRow,
        client: TrackerClient,
        engine: TorrentEngine,
    ): Result<PreparedTorrent> =
        client.torrentFile(row.topicId).mapCatching { bytes ->
            PreparedTorrent(
                torrentBytes = bytes,
                files = runCatching { engine.parseFiles(bytes) }.getOrDefault(emptyList()),
            )
        }

    /**
     * Search→download, step 2: enqueue the prepared torrent with the user's file
     * selection (empty = all files; the manager forwards it as engine skip priorities)
     * into [downloadManager] under a per-topic private staging dir resolved by
     * [stagingRoot]. The item's size reflects the selection, so the Downloads card's
     * byte counters are the selected payload, not the whole torrent. [onEnqueued] runs
     * after a successful enqueue — android brings its foreground service up there
     * (quota-gated), desktop downloads run in-process and pass the default no-op.
     * Failures propagate to the caller — the Search tab surfaces them as a snackbar.
     */
    suspend fun startDownload(
        row: SearchResultRow,
        prepared: PreparedTorrent,
        wantedFiles: Set<Int>,
        downloadManager: DownloadManager,
        stagingRoot: (topicId: Long) -> String,
        onEnqueued: (topicId: Long) -> Unit = {},
    ): Result<Unit> = runCatching {
        val selectedBytes = prepared.files
            .filter { it.index in wantedFiles }
            .sumOf { it.sizeBytes }
            .takeIf { it > 0 } ?: row.sizeBytes
        downloadManager.enqueue(
            DownloadRequest(
                id = "t-${row.topicId}",
                topicId = row.topicId,
                title = row.title,
                forumName = row.forumName,
                sizeBytes = selectedBytes,
            ),
            prepared.torrentBytes,
            stagingRoot(row.topicId),
            wantedFiles,
        )
        onEnqueued(row.topicId)
    }

    /**
     * Shared validate probe — the plan's "settings probe" (Task 9): one test search
     * through the tracker client. Used by the Settings tab (Validate button + manual
     * import) and — on desktop — by DesktopLoginScreen's SAVE path (save + validate via
     * the settings probe, then exit only when it passes).
     */
    suspend fun validateSession(client: () -> TrackerClient): Result<Unit> =
        client().search(SearchQuery(text = "test")).map { }
}
