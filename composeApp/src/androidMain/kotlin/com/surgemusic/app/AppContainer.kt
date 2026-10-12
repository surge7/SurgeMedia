package com.surgemusic.app

import android.content.Context
import android.content.Intent
import com.surgemusic.app.downloads.DownloadService
import com.surgemusic.app.downloads.NetworkGate
import com.surgemusic.app.session.KeystoreSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.settings.SharedPreferencesStore
import com.surgemusic.app.settings.SettingsStore
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.tracker.jvm.RutrackerClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manual DI: lazy singletons shared by all screens.
 * `engine`, `sessionStore`, `trackerClient()`, the search view model, `settings`
 * and `downloadManager` are wired (Tasks 5–7).
 */
object AppContainer {
    lateinit var appContext: Context

    val engine: TorrentEngine by lazy { JLibTorrentEngine() }

    /** Keystore-encrypted session persistence (Task 5). */
    val sessionStore: SessionRepository by lazy { KeystoreSessionStore(appContext) }

    private val client: RutrackerClient by lazy { RutrackerClient() }

    /**
     * Rutracker client singleton (Task 6).
     *
     * Mirror: stays the default base URL for now — the mirror setting lives in
     * [settings]; the Settings screen (Task 9) re-wires it here.
     * Session: re-imported from the session store on every call (idempotent — two volatile
     * field writes) so a search right after an in-app WebView login carries the fresh cookies.
     */
    fun trackerClient(): RutrackerClient {
        sessionStore.session.value?.let { s -> client.importSession(s.cookieHeader, s.userAgent) }
        return client
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
     * Download queue (Task 7, interim wiring): exists from here so [DownloadService] and
     * [NetworkGate] can observe it. The finalize hook is a placeholder — Task 8 replaces it
     * with the MediaStoreFinalizer.
     */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            engine = engine,
            maxConcurrent = settings.concurrentDownloads.value,
            finalize = { _, _ -> Result.success("staging kept (finalizer wired in Task 8)") },
        )
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
