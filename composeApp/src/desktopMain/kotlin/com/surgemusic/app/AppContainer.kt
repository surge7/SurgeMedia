package com.surgemusic.app

import com.surgemusic.app.downloads.DesktopFileCleaner
import com.surgemusic.app.downloads.DesktopFinalizer
import com.surgemusic.app.session.FileSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.settings.DesktopSettingsStore
import com.surgemusic.app.downloads.DesktopLibrary
import com.surgemusic.app.ui.downloads.DownloadsViewModel
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.app.ui.library.LibraryViewModel
import com.surgemusic.app.ui.settings.SettingsViewModel
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadRequest
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.jvm.RutrackerClient
import java.nio.file.Path

/**
 * Manual DI on desktop (plan Task 6) — the jvm twin of androidMain's [AppContainer]:
 * lazy singletons shared by all screens. `engine`, `sessionStore`, `settings`,
 * `trackerClient()`, the search/downloads/settings view models, `downloadManager` and the
 * search→download pipeline ([download]) are wired.
 *
 * Desktop v1 differences from android (by design): there is no [com.surgemusic.app.downloads.NetworkGate]
 * and no `DownloadService` — downloads run in-process, so the queue lives (and pauses) only
 * while the app does, and nothing starts a foreground notification. `wifiOnly` stays
 * editable in Settings but is not enforced on desktop.
 */
object AppContainer {
    val engine: TorrentEngine by lazy { JLibTorrentEngine() }

    /** Plain-file session persistence (Task 6; android uses KeystoreSessionStore). */
    val sessionStore: SessionRepository by lazy { FileSessionStore() }

    /** Properties-backed StateFlows (Task 6; android uses SettingsStore over SharedPreferences). */
    val settings: DesktopSettingsStore by lazy { DesktopSettingsStore() }

    private val clientLock = Any()

    @Volatile private var client: RutrackerClient? = null

    /** Base URL the current [client] was built with; a mirror edit (Settings, Task 9) rebuilds it. */
    @Volatile private var clientMirror: String? = null

    /**
     * Rutracker client singleton — same mirror-rebuild + session re-import pattern as
     * android. Mirror: rebuilt whenever the edited mirror differs from the current base
     * URL — the client's constructor is the only mirror seam it exposes. A blank setting
     * falls back to [RutrackerClient.DEFAULT_BASE_URL]. The old client is closed first (it
     * holds an OkHttp-backed Ktor engine). Session: re-imported from the session store on
     * every call (idempotent — two volatile field writes) so a search right after a
     * paste-cookie login carries the fresh cookies.
     */
    fun trackerClient(): RutrackerClient {
        val mirror = settings.mirror.value.trim().ifBlank { RutrackerClient.DEFAULT_BASE_URL }
        val active: RutrackerClient
        synchronized(clientLock) {
            var current = client
            if (current == null || clientMirror != mirror) {
                current?.close()
                current = RutrackerClient(baseUrl = mirror)
                client = current
                clientMirror = mirror
            }
            active = current!!
        }
        sessionStore.session.value?.let { s -> active.importSession(s.cookieHeader, s.userAgent) }
        return active
    }

    /** Search view model for the Search tab. */
    val searchViewModel: SearchViewModel by lazy {
        SearchViewModel(searchFn = { query, start ->
            trackerClient().search(SearchQuery(text = query, start = start))
        })
    }

    /**
     * Download queue: the finalize hook moves a finished torrent's staged files into
     * `~/Music/SurgeMedia/<sanitized title>` (audio) or `~/Movies/SurgeMedia/…` (video) via [DesktopFinalizer] (desktop twin of
     * MediaStoreFinalizer, spec §6 without MediaStore).
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            engine = engine,
            maxConcurrent = settings.concurrentDownloads.value,
            finalize = { item, staging -> DesktopFinalizer.finalize(item, staging) },
        )
    }

    /** Downloads view model for the Downloads tab (file deletion via DesktopFileCleaner). */
    val downloadsViewModel: DownloadsViewModel by lazy { DownloadsViewModel(downloadManager, DesktopFileCleaner) }

    /** Downloaded tab (finalized files on the media libraries). */
    val libraryViewModel: LibraryViewModel by lazy { LibraryViewModel(DesktopLibrary) }

    /**
     * Settings view model for the Settings tab: session management + manual import over
     * [sessionStore], network prefs over [settings], and the validate probe —
     * [validateSession] (plan Task 9).
     */
    val settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            sessionRepository = sessionStore,
            settings = settings,
            validateSession = { validateSession() },
        )
    }

    /**
     * Shared validate probe — the plan's "settings probe" (Task 9): one test search through
     * the tracker client. Used by the Settings tab (Validate button + manual import) and by
     * [com.surgemusic.app.ui.login.DesktopLoginScreen]'s SAVE path (Task 6: save +
     * validate via the settings probe, then exit only when it passes).
     */
    suspend fun validateSession(): Result<Unit> =
        trackerClient().search(SearchQuery(text = "test")).map { }

    /**
     * Full search→download pipeline (plan Task 8): fetch the .torrent, enqueue into
     * [downloadManager] with a per-topic private staging dir under `~/.surgemusic/staging`.
     * No service start on desktop (in-process queue — see the class KDoc). Failures (e.g.
     * expired session on `dl.php`) propagate to the caller — the Search tab surfaces them
     * as a snackbar.
     */
    suspend fun download(row: SearchResultRow): Result<Unit> =
        trackerClient().torrentFile(row.topicId).map { torrent ->
            val staging = stagingDir(row.topicId)
            downloadManager.enqueue(
                DownloadRequest(
                    id = "t-${row.topicId}",
                    topicId = row.topicId,
                    title = row.title,
                    forumName = row.forumName,
                    sizeBytes = row.sizeBytes,
                ),
                torrent,
                staging,
            )
        }

    private fun stagingDir(topicId: Long): String =
        Path.of(System.getProperty("user.home"), DIR_NAME, STAGING_NAME, topicId.toString()).toString()

    private const val DIR_NAME = ".surgemusic"
    private const val STAGING_NAME = "staging"
}
