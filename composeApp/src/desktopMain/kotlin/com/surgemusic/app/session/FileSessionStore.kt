package com.surgemusic.app.session

import com.surgemusic.app.io.storeAtomicPrivate
import java.io.ByteArrayOutputStream
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
 * [SessionRepository] on desktop (plan Task 6): session Properties carried by an OS
 * credential vault when one exists ([SessionVaults.create] — DPAPI on Windows, login
 * Keychain via `security` on macOS, `secret-tool` on Linux; [vault] is null = no vault on
 * this host). The serialized Properties string is the vault payload — base64/encrypted in
 * transport, never on disk in plaintext. Writes only delete the plaintext
 * `~/.surgemusic/session.properties` after a *confirmed* vault save, so the accepted v1
 * scope — plaintext cookie under the user's home, POSIX 0600, temp-file + atomic move
 * ([storeAtomicPrivate]) — stays the fallback when the vault is unavailable or fails,
 * and a pre-vault file is migrated to the vault (then deleted) on first load. Desktop v1
 * had no keystore seam at all (cookie pasted by hand; androidMain encrypts via
 * AndroidKeyStore); an existing session loads on init; a missing, unreadable or malformed
 * vault payload/file is treated as logged out.
 *
 * Write discipline (androidMain `KeystoreSessionStore` flushes via SharedPreferences
 * `apply()`, off the calling thread): `save`/`clear` update the [StateFlow] synchronously
 * and flush the vault/file FIFO on the single-threaded daemon [persistScope] — no file IO
 * on the UI (AWT EDT) thread, last write wins on disk, a failed flush is swallowed like
 * `apply()`.
 */
class FileSessionStore(
    private val homeDir: Path = Path.of(System.getProperty("user.home")),
    private val vault: SessionVault? = null,
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
            // Vault first; only a confirmed vault write removes the plaintext file.
            // Anything else (no vault, save failure) keeps the 0600 file — temp file +
            // atomic move + POSIX 0600 (see [storeAtomicPrivate]): the file carries the
            // bb_session cookie in plaintext, so private perms from birth and no torn
            // intermediate state both matter.
            val vaulted = vault != null && runCatching { vault.save(serialize(props)) }.getOrDefault(false)
            runCatching {
                if (vaulted) Files.deleteIfExists(propsFile) else props.storeAtomicPrivate(propsFile)
            } // failed flush is swallowed, like SharedPreferences.apply()
        }
    }

    override fun clear() {
        _session.value = null
        persistScope.launch {
            vault?.let { v -> runCatching { v.clear() } } // wipe the OS-vaulted copy too
            runCatching { Files.deleteIfExists(propsFile) } // async + silent on failure, like apply()
        }
    }

    private fun readSession(): Session? {
        // Vault wins whenever it holds a readable payload.
        val vaulted = vault?.let { v -> runCatching { v.load() }.getOrNull()?.let { parsePayload(it) } }
        if (vaulted != null) return vaulted
        // Plaintext fallback: pre-vault file, or a vault-unavailable save.
        val props = Properties()
        val loaded = runCatching {
            if (Files.exists(propsFile)) {
                Files.newInputStream(propsFile).use { props.load(it) }
            }
        }.isSuccess
        if (!loaded) return null
        val cookieHeader = props.getProperty(KEY_COOKIE) ?: return null
        val savedAt = props.getProperty(KEY_SAVED_AT)?.toLongOrNull() ?: return null
        if (vault != null) {
            // One-time migration: copy the plaintext payload into the vault and — only on
            // a confirmed vault write — delete the plaintext file. On [persistScope] to
            // keep the save/clear flush discipline (no IO off the init thread's contract).
            persistScope.launch {
                val stored = runCatching { vault.save(serialize(props)) }.getOrDefault(false)
                if (stored) runCatching { Files.deleteIfExists(propsFile) }
            }
        }
        return Session(
            cookieHeader = cookieHeader,
            userAgent = props.getProperty(KEY_USER_AGENT).orEmpty(),
            savedAtEpochMs = savedAt,
        )
    }

    /**
     * The vault payload: the same Properties text as the fallback file, as a string.
     * ISO-8859-1 because that is `Properties.load/store`'s stream charset — the exact
     * bytes that round-trip through the file path round-trip through the payload too.
     */
    private fun serialize(props: Properties): String =
        ByteArrayOutputStream().also { props.store(it, null) }.toByteArray().toString(Charsets.ISO_8859_1)

    /** Inverse of [serialize]; a malformed payload is treated as logged out (like the file). */
    private fun parsePayload(payload: String): Session? {
        val props = Properties()
        runCatching { props.load(payload.toByteArray(Charsets.ISO_8859_1).inputStream()) }
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
