package com.surgemusic.app.session

import com.surgemusic.app.io.storeAtomicPrivate
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [SessionRepository] on desktop (plan Task 6): plain `java.util.Properties` at
 * `~/.surgemusic/session.properties`. Desktop v1 has no OS keystore seam — the
 * cookie/user-agent are devtools-pasted by hand and the file lives under the user's own
 * home, scoped by the OS to the user account (androidMain encrypts via AndroidKeyStore).
 * An existing session loads on init; a missing, unreadable or malformed file is treated
 * as logged out.
 *
 * Write discipline (androidMain `KeystoreSessionStore` flushes via SharedPreferences
 * `apply()`, off the calling thread): `save`/`clear` update the [StateFlow] synchronously
 * and flush the file FIFO on the single-threaded daemon [persistScope] — no file IO
 * on the UI (AWT EDT) thread, last write wins on disk, a failed flush is swallowed like
 * `apply()`. Flushes are atomic (temp file + rename) and the file is created 0600 — the
 * bb_session cookie is stored in plaintext, so private perms are the accepted scope
 * (a keystore/vault migration stays out of scope for desktop v1).
 */
class FileSessionStore(
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
) : SessionRepository {
    private val propsFile = homeDir.resolve(DIR).resolve(FILE)

    /** All disk writes on one daemon thread: flush order matches save/clear order. */
    private val persistScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r -> Thread(r, "surgemusic-session-persist").apply { isDaemon = true } }
            .asCoroutineDispatcher(),
    )

    private val _session = MutableStateFlow(readSession())
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override fun save(cookieHeader: String, userAgent: String) {
        val savedAt = nowEpochMs()
        val props = Properties().apply {
            setProperty(KEY_COOKIE, cookieHeader)
            setProperty(KEY_USER_AGENT, userAgent)
            setProperty(KEY_SAVED_AT, savedAt.toString())
        }
        _session.value = Session(cookieHeader, userAgent, savedAt)
        persistScope.launch {
            runCatching {
                // Temp file + atomic move + POSIX 0600 (see [storeAtomicPrivate]): the
                // file carries the bb_session cookie in plaintext, so private perms from
                // birth and no torn intermediate state both matter.
                props.storeAtomicPrivate(propsFile)
            } // failed flush is swallowed, like SharedPreferences.apply()
        }
    }

    override fun clear() {
        _session.value = null
        persistScope.launch {
            runCatching { Files.deleteIfExists(propsFile) } // async + silent on failure, like apply()
        }
    }

    private fun readSession(): Session? {
        val props = Properties()
        val loaded = runCatching {
            if (Files.exists(propsFile)) {
                Files.newInputStream(propsFile).use { props.load(it) }
            }
        }.isSuccess
        if (!loaded) return null
        val cookieHeader = props.getProperty(KEY_COOKIE) ?: return null
        val savedAt = props.getProperty(KEY_SAVED_AT)?.toLongOrNull() ?: return null
        return Session(
            cookieHeader = cookieHeader,
            userAgent = props.getProperty(KEY_USER_AGENT).orEmpty(),
            savedAtEpochMs = savedAt,
        )
    }

    private companion object {
        const val DIR = ".surgemusic"
        const val FILE = "session.properties"
        const val KEY_COOKIE = "cookie_header"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_SAVED_AT = "saved_at_epoch_ms"
    }
}
