package com.surgemusic.app

import android.content.Context
import com.surgemusic.app.session.KeystoreSessionStore
import com.surgemusic.app.session.SessionRepository
import com.surgemusic.core.downloads.DownloadManager
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.jvm.JLibTorrentEngine

/**
 * Manual DI: lazy singletons shared by all screens.
 * `engine` and `sessionStore` are wired now; `trackerClient()`, `settings` and
 * `downloadManager` are filled in by Tasks 6–8.
 */
object AppContainer {
    lateinit var appContext: Context

    val engine: TorrentEngine by lazy { JLibTorrentEngine() }

    /** Keystore-encrypted session persistence (Task 5). */
    val sessionStore: SessionRepository by lazy { KeystoreSessionStore(appContext) }

    /** Wired in Task 6 (`RutrackerClient` with mirror + session import). */
    fun trackerClient(): Nothing = TODO("AppContainer.trackerClient() is wired in Task 6")

    /** Wired in Task 7 (SharedPreferences-backed `SettingsStore`). `Nothing` = throws until wired. */
    val settings: Nothing by lazy { TODO("AppContainer.settings is wired in Task 7") }

    /** Wired in Task 8 (queue + MediaStore finalize hook). */
    val downloadManager: DownloadManager by lazy { TODO("AppContainer.downloadManager is wired in Task 8") }
}
