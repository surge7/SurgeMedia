package com.surgemusic.app.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SessionRepository] on desktop (plan Task 6): plain `java.util.Properties` at
 * `~/.surgemusic/session.properties`. Desktop v1 has no OS keystore seam — the
 * cookie/user-agent are devtools-pasted by hand and the file lives under the user's own
 * home, scoped by the OS to the user account (androidMain encrypts via AndroidKeyStore).
 * An existing session loads on init; a missing, unreadable or malformed file is treated
 * as logged out. Thread safety: the same synchronized persist-then-emit discipline as
 * `DesktopSettingsStore` — `save`/`clear` hold [ioLock] across the file write and the
 * flow update.
 */
class FileSessionStore(
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
) : SessionRepository {
    private val propsFile = homeDir.resolve(DIR).resolve(FILE)
    private val ioLock = Any()

    private val _session = MutableStateFlow(readSession())
    override val session: StateFlow<Session?> = _session.asStateFlow()

    override fun save(cookieHeader: String, userAgent: String) {
        val savedAt = nowEpochMs()
        synchronized(ioLock) {
            val props = Properties().apply {
                setProperty(KEY_COOKIE, cookieHeader)
                setProperty(KEY_USER_AGENT, userAgent)
                setProperty(KEY_SAVED_AT, savedAt.toString())
            }
            Files.createDirectories(propsFile.parent)
            Files.newOutputStream(propsFile).use { props.store(it, null) }
            _session.value = Session(cookieHeader, userAgent, savedAt)
        }
    }

    override fun clear() {
        synchronized(ioLock) {
            Files.deleteIfExists(propsFile)
            _session.value = null
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
