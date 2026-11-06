package com.surgemusic.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import com.surgemusic.app.downloads.AndroidQueuePersistence
import com.surgemusic.app.downloads.DataSyncQuota
import com.surgemusic.app.downloads.DownloadService
import com.surgemusic.app.downloads.MediaStoreCleaner
import com.surgemusic.app.downloads.MediaStoreFinalizer
import com.surgemusic.app.downloads.NetworkGate
import com.surgemusic.app.downloads.isEffectivelyUnmetered
import com.surgemusic.app.session.KeystoreSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.settings.SharedPreferencesStore
import com.surgemusic.app.settings.SettingsStore
import com.surgemusic.app.downloads.MediaStoreLibrary
import com.surgemusic.app.tracker.TrackerAppFactory
import com.surgemusic.app.ui.downloads.DownloadsViewModel
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.app.ui.library.LibraryViewModel
import com.surgemusic.app.ui.settings.SettingsViewModel
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.downloads.DownloadState
import com.surgemusic.core.downloads.PreparedTorrent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.model.SearchResultRow
import com.surgemusic.core.tracker.TrackerClient
import com.surgemusic.core.tracker.jvm.RutrackerClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual DI: lazy singletons shared by all screens, with the platform-agnostic members
 * ([trackerClient], [prepare], [startDownload], [validateSession]) delegating to
 * [TrackerAppFactory].
 * `engine`, `sessionStore`, `trackerClient()`, the search/downloads view models,
 * `settings`, `downloadManager` and the search→download pipeline ([download]) are
 * wired (Tasks 5–8).
 */
actual object AppContainer {
    lateinit var appContext: Context

    /**
     * OS-language-change tick (see the commonMain expect declaration): static 0 on
     * android — the OS recreates the process on a language change, so the strings
     * re-resolve through the restart and no polling is needed.
     */
    actual val localeTick: StateFlow<Int> = MutableStateFlow(0)

    val engine: TorrentEngine by lazy { JLibTorrentEngine() }

    /** Keystore-encrypted session persistence (Task 5). */
    val sessionStore: SessionRepository by lazy { KeystoreSessionStore(appContext) }

    /**
     * Tracker client singleton (Task 6; mirror wiring per Task 9; the [TrackerClient]
     * seam — mirror-rebuild + session re-import moved verbatim to
     * [TrackerAppFactory.trackerClient], shared with desktop). A blank mirror setting
     * falls back to the rutracker default ([RutrackerClient.DEFAULT_BASE_URL], the one
     * concrete constant left here); the session is re-imported from [sessionStore] on
     * every call so a search right after an in-app WebView login carries the fresh
     * cookies.
     */
    fun trackerClient(): TrackerClient = TrackerAppFactory.trackerClient(
        base = settings.mirror.value.trim().ifBlank { RutrackerClient.DEFAULT_BASE_URL },
        session = { sessionStore.session.value?.let { it.cookieHeader to it.userAgent } },
    )

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
     * SAF folder picker installed by MainActivity (Settings ▸ Storage): shows the
     * system folder chooser and resolves to the chosen tree URI string, or null when
     * cancelled. Null until the activity installs it (DI singletons outlive it).
     */
    @Volatile
    var folderPicker: (suspend (isVideo: Boolean) -> String?)? = null

    /**
     * Pending pick parked by [folderPicker] and completed by MainActivity's
     * ActivityResultLauncher callback (tree URI string, or null when the user abandons
     * the picker). Lives on this process singleton, NOT the activity, so a configuration
     * change / recreation while the system chooser is showing can't drop the awaiting
     * coroutine — the recreated activity's launcher still resolves the same deferred.
     */
    @Volatile
    var folderPickDeferred: CompletableDeferred<String?>? = null

    /**
     * Download queue (Task 8 wiring): the finalize hook moves a finished torrent's staged
     * files into `Music/SurgeMedia/` / `Movies/SurgeMedia/` — or the user's chosen
     * folders — via [MediaStoreFinalizer] (spec §6, scoped storage). `maxConcurrent` is
     * the live settings flow, so the concurrent-downloads slider takes effect without an
     * app restart; queue persistence survives app restarts
     * (`filesDir/queue.json` via [AndroidQueuePersistence]).
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            engine = engine,
            maxConcurrent = settings.concurrentDownloads,
            persistence = AndroidQueuePersistence(appContext),
            finalize = { item, staging -> MediaStoreFinalizer(appContext, settings).finalize(item, staging) },
        )
    }

    /** Downloads view model for the Downloads tab (Task 8; file deletion via MediaStore). */
    val downloadsViewModel: DownloadsViewModel by lazy {
        DownloadsViewModel(downloadManager, MediaStoreCleaner(appContext, settings))
    }

    /** Downloaded tab (finalized files on the media libraries). */
    val libraryViewModel: LibraryViewModel by lazy { LibraryViewModel(MediaStoreLibrary(appContext, settings)) }

    /**
     * Settings view model for the Settings tab (Task 9): session management + manual import
     * over [sessionStore], network prefs over [settings], the validate probe —
     * [validateSession] — and the SAF folder picker behind
     * Settings ▸ Storage (installed by MainActivity).
     */
    val settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            sessionRepository = sessionStore,
            settings = settings,
            validateSession = { validateSession() },
            pickFolder = { isVideo -> folderPicker?.invoke(isVideo) },
        )
    }

    /**
     * Shared validate probe (Task 9): one test search through the tracker client —
     * [TrackerAppFactory.validateSession]. Used by the Settings tab (Validate button +
     * manual import).
     */
    suspend fun validateSession(): Result<Unit> = TrackerAppFactory.validateSession { trackerClient() }

    /**
     * Search→download, step 1 (plan Task 8, split for the file chooser):
     * [TrackerAppFactory.prepare] fetches the .torrent for [row] and parses its file
     * list (a parse failure degrades to an empty list — the chooser is skipped and
     * everything downloads).
     */
    suspend fun prepare(row: SearchResultRow): Result<PreparedTorrent> =
        TrackerAppFactory.prepare(row, trackerClient(), engine)

    /**
     * Search→download, step 2: [TrackerAppFactory.startDownload] enqueues the prepared
     * torrent with the user's file selection (empty = all files) with a per-topic
     * private staging dir under `getExternalFilesDir("staging")`, then — via
     * `onEnqueued`, which runs only after a successful enqueue — brings the foreground
     * service up ([startDownloadService], quota-gated). The item's size reflects the
     * selection, so the Downloads card's byte counters are the selected payload, not
     * the whole torrent. Failures (e.g. an expired session on `dl.php`) propagate to
     * the caller — the Search tab surfaces them as a snackbar.
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
        stagingRoot = { topicId ->
            File(appContext.getExternalFilesDir("staging"), topicId.toString()).absolutePath
        },
        onEnqueued = { startDownloadService() },
    )

    private val networkGate: NetworkGate by lazy {
        NetworkGate(appContext) { downloadManager.items.value }
    }
    private val networkGateRegistered = AtomicBoolean(false)

    /**
     * dataSync FGS budget (6h per 24h rolling window on Android 15): DownloadService
     * accumulates its wall time here and self-pauses at the threshold; this gate
     * withholds the FGS once the budget is spent. Backed by the settings prefs file
     * (`data_sync_*` keys) so the count survives process death.
     */
    val dataSyncQuota: DataSyncQuota by lazy {
        DataSyncQuota(
            SharedPreferencesStore(appContext.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)),
        )
    }

    private val quotaLock = Any()

    /**
     * Ids the quota pause stopped (NetworkGate-style self-tracked ids — no DownloadItem
     * field, "acceptable v1" per plan). Only items that were DOWNLOADING land here, so
     * a user's manual pause is never overridden by [resumeQuotaPaused].
     */
    private val quotaPausedIds = mutableSetOf<String>()

    /**
     * Called by DownloadService's periodic check when only the 15-minute quota margin
     * is left: snapshots the DOWNLOADING ids as quota-paused, then pauses the queue.
     * QUEUED items stay QUEUED — with the FGS gone they wait for the app's next
     * foreground spell ([resumeQuotaPaused]) or for the 24h window rollover.
     */
    fun pauseForQuota() {
        val ids = downloadManager.items.value
            .filter { it.state == DownloadState.DOWNLOADING }
            .map { it.request.id }
        synchronized(quotaLock) { quotaPausedIds += ids }
        downloadManager.pauseAll()
        Log.i(TAG, "quota pause: ${ids.size} item(s), remaining=${dataSyncQuota.remainingMs()} ms")
    }

    /**
     * Foreground heal for the quota pause (MainActivity.onResume, next to
     * [healNetworkGate]): quota-paused items download again while the app is visible —
     * foreground app time consumes no dataSync FGS quota, so no service is needed.
     * Wi-Fi-only is honored: on a metered network the ids stay parked until
     * [startNetworkGate]'s unmetered branch calls this again. Cleared ids resume via
     * the manager (its resume guard makes re-runs harmless).
     */
    fun resumeQuotaPaused() {
        val ids = synchronized(quotaLock) { quotaPausedIds.toList() }
        if (ids.isEmpty()) return
        if (settings.wifiOnly.value && !activeNetworkUnmetered()) {
            Log.i(TAG, "quota resume deferred: metered network with Wi-Fi-only on")
            return
        }
        synchronized(quotaLock) { quotaPausedIds.clear() }
        ids.forEach { downloadManager.resume(it) }
        Log.i(TAG, "quota resume: ${ids.size} item(s)")
    }

    private fun activeNetworkUnmetered(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.isEffectivelyUnmetered()
    }

    /**
     * Foreground start; called from MainActivity.onResume (app is in the foreground)
     * and after each enqueue. Quota gate: with only the 15-minute dataSync margin left
     * the FGS is NOT started — Android 15 would otherwise run it into the 6h timeout
     * and force-stop the app mid-write. Items stay QUEUED/PAUSED with the quota reason
     * ([pauseForQuota]); they run while the app is foregrounded ([resumeQuotaPaused])
     * and the FGS comes back once the 24h window rolls over (next call with budget).
     */
    fun startDownloadService() {
        if (dataSyncQuota.shouldPause()) {
            Log.i(TAG, "dataSync quota spent (remaining=${dataSyncQuota.remainingMs()} ms) — FGS withheld")
            return
        }
        appContext.startForegroundService(Intent(appContext, DownloadService::class.java))
    }

    /**
     * Registers the Wi-Fi gate once per process (MainActivity.onCreate).
     * Lost unmetered + Wi-Fi-only → pause all; regained → resume only gate-auto-paused items.
     * Transitions are logged under "SurgeGate" for on-device diagnosis.
     */
    fun startNetworkGate() {
        if (!networkGateRegistered.compareAndSet(false, true)) return
        networkGate.register { hasUnmetered ->
            Log.i(TAG, "unmetered=$hasUnmetered wifiOnly=${settings.wifiOnly.value}")
            if (!hasUnmetered && settings.wifiOnly.value) {
                val paused = networkGate.pauseAndTrack { downloadManager.pauseAll() }
                if (paused.isNotEmpty()) Log.i(TAG, "gate paused ${paused.size} item(s): $paused")
            } else if (hasUnmetered) {
                val resumed = networkGate.resumeAutoPaused { downloadManager.resume(it) }
                if (resumed.isNotEmpty()) Log.i(TAG, "gate resumed ${resumed.size} item(s): $resumed")
                // Quota-paused ids parked by Wi-Fi-only + metered resume with the network.
                resumeQuotaPaused()
            }
        }
    }

    /**
     * Foreground heal (MainActivity.onResume): if the current default network is effectively
     * unmetered, resume anything the gate auto-paused. Covers pauses fired on a transient
     * capability snapshot with no subsequent unmetered callback (e.g. VPN reconnects).
     */
    fun healNetworkGate() {
        if (!networkGateRegistered.get()) return
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return
        if (caps.isEffectivelyUnmetered()) {
            val resumed = networkGate.resumeAutoPaused { downloadManager.resume(it) }
            if (resumed.isNotEmpty()) Log.i(TAG, "gate heal resumed ${resumed.size} item(s)")
        }
    }

    private const val PREFS_SETTINGS = "surgemusic_settings"
    private const val TAG = "SurgeGate"
}
