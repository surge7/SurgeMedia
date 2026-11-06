package com.surgemusic.app

import com.surgemusic.app.downloads.DesktopFileCleaner
import com.surgemusic.app.downloads.DesktopFinalizer
import com.surgemusic.app.downloads.DesktopQueuePersistence
import com.surgemusic.app.locale.DesktopLocaleMonitor
import com.surgemusic.app.session.FileSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.session.SessionVaults
import com.surgemusic.app.settings.DesktopSettingsStore
import com.surgemusic.app.settings.DirectoryPicker
import com.surgemusic.app.downloads.DesktopLibrary
import com.surgemusic.app.tracker.TrackerAppFactory
import com.surgemusic.app.ui.downloads.DownloadsViewModel
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.app.ui.library.LibraryViewModel
import com.surgemusic.app.ui.settings.SettingsViewModel
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.PreparedTorrent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.jvm.RutrackerClient
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Manual DI on desktop (plan Task 6) — the jvm twin of androidMain's [AppContainer]:
 * lazy singletons shared by all screens, with the platform-agnostic members
 * ([trackerClient], [prepare], [startDownload], [validateSession]) delegating to
 * [TrackerAppFactory]. `engine`, `sessionStore`, `settings`, the search/downloads/
 * settings view models, `downloadManager` and the search→download pipeline are wired.
 *
 * Desktop v1 differences from android (by design): there is no [com.surgemusic.app.downloads.NetworkGate]
 * and no `DownloadService` — downloads run in-process, so the queue lives (and pauses) only
 * while the app does, and nothing starts a foreground notification. `wifiOnly` stays
 * editable in Settings but is not enforced on desktop.
 */
actual object AppContainer {
    val engine: TorrentEngine by lazy { JLibTorrentEngine() }

    /**
     * Session persistence (Task 6; android uses KeystoreSessionStore): OS credential vault
     * when the host has one (DPAPI / Keychain / secret-tool), 0600 plaintext file as the
     * fallback — see [FileSessionStore].
     */
    val sessionStore: SessionRepository by lazy { FileSessionStore(vault = SessionVaults.create()) }

    /** Properties-backed StateFlows (Task 6; android uses SettingsStore over SharedPreferences). */
    val settings: DesktopSettingsStore by lazy { DesktopSettingsStore() }

    /** Backing tick for [localeTick]; bumped by [localeMonitor] on an OS-language change. */
    private val localeTickState = MutableStateFlow(0)

    /** OS-language-change tick (see the commonMain expect declaration). */
    actual val localeTick: StateFlow<Int> get() = localeTickState

    /**
     * OS-language watcher feeding [localeTick]: started at desktop startup (Main.kt) and
     * stopped from its shutdown hook — the container just hosts the pair.
     */
    private val localeMonitor by lazy { DesktopLocaleMonitor(localeTickState) }

    /** Starts the OS-language poll loop (desktop startup; see [DesktopLocaleMonitor]). */
    fun startLocaleMonitor() = localeMonitor.start()

    /** Stops the OS-language poll loop (shutdown hook — no exit path leaks it). */
    fun stopLocaleMonitor() = localeMonitor.stop()

    /**
     * Rutracker client singleton — mirror-rebuild + session re-import moved verbatim to
     * [TrackerAppFactory.trackerClient] (shared with android). A blank mirror setting
     * falls back to [RutrackerClient.DEFAULT_BASE_URL]; the session is re-imported from
     * [sessionStore] on every call so a search right after a paste-cookie login carries
     * the fresh cookies.
     */
    fun trackerClient(): RutrackerClient = TrackerAppFactory.trackerClient(
        base = settings.mirror.value.trim().ifBlank { RutrackerClient.DEFAULT_BASE_URL },
        session = { sessionStore.session.value?.let { it.cookieHeader to it.userAgent } },
    )

    /** Search view model for the Search tab. */
    val searchViewModel: SearchViewModel by lazy {
        SearchViewModel(searchFn = { query, start ->
            trackerClient().search(SearchQuery(text = query, start = start))
        })
    }

    /**
     * Download queue: the finalize hook moves a finished torrent's staged files into the
     * active music / video folders (Settings ▸ Storage; built-ins `~/Music/SurgeMedia`
     * and `~/Movies|Videos/SurgeMedia` — macOS `Movies`, Windows/Linux `Videos`) via
     * [DesktopFinalizer] (desktop twin of MediaStoreFinalizer, spec §6 without MediaStore).
     * `maxConcurrent` is the live settings flow, so the concurrent-downloads slider takes
     * effect without an app restart; queue persistence survives app restarts
     * (`~/.surgemusic/queue.json` via [DesktopQueuePersistence]).
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            engine = engine,
            maxConcurrent = settings.concurrentDownloads,
            persistence = DesktopQueuePersistence(),
            finalize = { item, staging -> DesktopFinalizer(settings).finalize(item, staging) },
        )
    }

    /** Downloads view model for the Downloads tab (file deletion via DesktopFileCleaner). */
    val downloadsViewModel: DownloadsViewModel by lazy {
        DownloadsViewModel(downloadManager, DesktopFileCleaner(settings))
    }

    /** Downloaded tab (finalized files on the media libraries). */
    val libraryViewModel: LibraryViewModel by lazy { LibraryViewModel(DesktopLibrary(settings)) }

    /**
     * Settings view model for the Settings tab: session management + manual import over
     * [sessionStore], network prefs over [settings], the validate probe —
     * [validateSession] (plan Task 9) — and the native folder picker behind
     * Settings ▸ Storage (the AWT dialog must run on the EDT, i.e. Dispatchers.Main).
     */
    val settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            sessionRepository = sessionStore,
            settings = settings,
            validateSession = { validateSession() },
            pickFolder = { isVideo ->
                withContext(Dispatchers.Main) {
                    DirectoryPicker.pick(
                        if (isVideo) "Choose video folder" else "Choose music folder",
                    )
                }
            },
        )
    }

    /**
     * Shared validate probe — the plan's "settings probe" (Task 9): one test search
     * through [TrackerAppFactory.validateSession]. Used by the Settings tab (Validate
     * button + manual import) and by [com.surgemusic.app.ui.login.DesktopLoginScreen]'s
     * SAVE path (Task 6: save + validate via the settings probe, then exit only when it
     * passes).
     */
    suspend fun validateSession(): Result<Unit> = TrackerAppFactory.validateSession { trackerClient() }

    /**
     * Search→download, step 1 (plan Task 8, split for the file chooser): [TrackerAppFactory.prepare]
     * fetches the .torrent for [row] and parses its file list (a parse failure degrades
     * to an empty list — the chooser is skipped and everything downloads).
     */
    suspend fun prepare(row: SearchResultRow): Result<PreparedTorrent> =
        TrackerAppFactory.prepare(row, trackerClient(), engine)

    /**
     * Search→download, step 2: [TrackerAppFactory.startDownload] enqueues the prepared
     * torrent with the user's file selection into [downloadManager] with a per-topic
     * private staging dir under `~/.surgemusic/staging`. No foreground service on
     * desktop — the default no-op [TrackerAppFactory.startDownload]'s `onEnqueued` hook
     * keeps this the no-FGS path. Failures propagate to the caller — the Search tab
     * surfaces them as a snackbar.
     */
    suspend fun startDownload(
        row: SearchResultRow,
        prepared: PreparedTorrent,
        wantedFiles: Set<Int>,
    ): Result<Unit> = TrackerAppFactory.startDownload(
        row = row,
        prepared = prepared,
        wantedFiles = wantedFiles,
        downloadManager = downloadManager,
        stagingRoot = ::stagingDir,
    )

    private fun stagingDir(topicId: Long): String =
        Path.of(System.getProperty("user.home"), DIR_NAME, STAGING_NAME, topicId.toString()).toString()

    private const val DIR_NAME = ".surgemusic"
    private const val STAGING_NAME = "staging"
}
