package com.surgemusic.app

import android.content.Context
import com.surgemusic.app.session.KeystoreSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.app.ui.search.SearchViewModel
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine
import com.surgemusic.core.model.SearchQuery
import com.surgemusic.core.tracker.jvm.RutrackerClient

/**
 * Manual DI: lazy singletons shared by all screens.
 * `engine`, `sessionStore`, `trackerClient()` and the search view model are wired;
 * `settings` and `downloadManager` are filled in by Tasks 7–8.
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
     * Mirror: stays the default base URL for now — the mirror setting lives in the
     * `SettingsStore` that Task 7 creates; Tasks 7/9 re-wire it here.
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

    /** Wired in Task 7 (SharedPreferences-backed `SettingsStore`). `Nothing` = throws until wired. */
    val settings: Nothing by lazy { TODO("AppContainer.settings is wired in Task 7") }

    /** Wired in Task 8 (queue + MediaStore finalize hook). */
    val downloadManager: DownloadManager by lazy { TODO("AppContainer.downloadManager is wired in Task 8") }
}
