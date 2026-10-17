package com.surgemusic.app

import android.content.Context
import android.content.Intent
import com.surgemusic.app.downloads.DownloadService
import com.surgemusic.app.downloads.MediaStoreCleaner
import com.surgemusic.app.downloads.MediaStoreFinalizer
import com.surgemusic.app.downloads.NetworkGate
import com.surgemusic.app.session.KeystoreSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.settings.SharedPreferencesStore
import com.surgemusic.app.settings.SettingsStore
import com.surgemusic.app.ui.downloads.DownloadsViewModel
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.app.ui.settings.SettingsViewModel
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadRequest
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.jvm.RutrackerClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual DI: lazy singletons shared by all screens.
 * `engine`, `sessionStore`, `trackerClient()`, the search/downloads view models,
 * `settings`, `downloadManager` and the search→download pipeline ([download]) are
 * wired (Tasks 5–8).
 */
object AppContainer {
    lateinit var appContext: Context

    val engine: TorrentEngine by lazy { JLibTorrentEngine() }

    /** Keystore-encrypted session persistence (Task 5). */
    val sessionStore: SessionRepository by lazy { KeystoreSessionStore(appContext) }

    private val clientLock = Any()

    @Volatile private var client: RutrackerClient? = null

    /** Base URL the current [client] was built with; a mirror edit (Settings, Task 9) rebuilds it. */
    @Volatile private var clientMirror: String? = null

    /**
     * Rutracker client singleton (Task 6; mirror wiring per Task 9).
     *
     * Mirror: rebuilt whenever the edited mirror differs from the current base URL — the
     * client's constructor is the only mirror seam it exposes. A blank setting falls back
     * to [RutrackerClient.DEFAULT_BASE_URL]. The old client is closed first (it holds an
     * OkHttp-backed Ktor engine). Session: re-imported from the session store on every call
     * (idempotent — two volatile field writes) so a search right after an in-app WebView
     * login carries the fresh cookies.
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

    /** Search view model for the Search tab (Task 6). */
    val searchViewModel: SearchViewModel by lazy {
        SearchViewModel(searchFn = { query, start ->
            trackerClient().search(SearchQuery(text = query, start = start))
        })
    }

    /** App settings (Task 7): SharedPreferences-backed StateFlows. */
    val settings: SettingsStore by lazy {
        SettingsStore(
            SharedPreferencesStore(appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)),
        )
    }

    /**
     * Download queue (Task 8 wiring): the finalize hook moves a finished torrent's staged
     * files into `Music/SurgeMusic/` via [MediaStoreFinalizer] (spec §6, scoped storage).
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            engine = engine,
            maxConcurrent = settings.concurrentDownloads.value,
            finalize = { item, staging -> MediaStoreFinalizer(appContext).finalize(item, staging) },
        )
    }

    /** Downloads view model for the Downloads tab (Task 8; file deletion via MediaStore). */
    val downloadsViewModel: DownloadsViewModel by lazy { DownloadsViewModel(downloadManager, MediaStoreCleaner(appContext)) }

    /**
     * Settings view model for the Settings tab (Task 9): session management + manual import
     * over [sessionStore], network prefs over [settings], and the validate probe —
     * `trackerClient().search(SearchQuery("test"))` (plan Task 9).
     */
    val settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            sessionRepository = sessionStore,
            settings = settings,
            validateSession = { trackerClient().search(SearchQuery(text = "test")).map { } },
        )
    }

    /**
     * Full search→download pipeline (plan Task 8): fetch the .torrent, enqueue into
     * [downloadManager] with a per-topic private staging dir, then bring the foreground
     * service up. Failures (e.g. expired session on `dl.php`) propagate to the caller —
     * the Search tab surfaces them as a snackbar.
     */
    suspend fun download(row: SearchResultRow): Result<Unit> =
        trackerClient().torrentFile(row.topicId).mapCatching { torrent ->
            val staging = File(appContext.getExternalFilesDir("staging"), row.topicId.toString())
                .absolutePath
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
            startDownloadService()
        }

    private val networkGate: NetworkGate by lazy {
        NetworkGate(appContext) { downloadManager.items.value }
    }
    private val networkGateRegistered = AtomicBoolean(false)

    /** Foreground start; called from MainActivity.onResume (app is in the foreground). */
    fun startDownloadService() {
        appContext.startForegroundService(Intent(appContext, DownloadService::class.java))
    }

    /**
     * Registers the Wi-Fi gate once per process (MainActivity.onCreate).
     * Lost unmetered + Wi-Fi-only → pause all; regained → resume only gate-auto-paused items.
     */
    fun startNetworkGate() {
        if (!networkGateRegistered.compareAndSet(false, true)) return
        networkGate.register { hasUnmetered ->
            if (!hasUnmetered && settings.wifiOnly.value) {
                networkGate.pauseAndTrack { downloadManager.pauseAll() }
            } else if (hasUnmetered) {
                networkGate.resumeAutoPaused { downloadManager.resume(it) }
            }
        }
    }

    private const val PREFS_SETTINGS = "surgemusic_settings"
}
