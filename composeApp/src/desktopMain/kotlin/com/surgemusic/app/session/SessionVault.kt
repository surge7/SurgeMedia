package com.surgemusic.app.session

import com.surgemusic.app.IsMacOs
import com.surgemusic.app.IsWindows
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OS credential vault for the desktop session cookie — the desktop twin of androidMain's
 * AndroidKeyStore-backed `KeystoreSessionStore` crypto. Implementations store one opaque
 * payload string ([FileSessionStore] passes the serialized session Properties) behind the
 * OS-protected storage of the day:
 *
 * - Windows: DPAPI (`Crypt32Util`), current-user scope, base64 blob at
 *   `~/.surgemusic/session.vault` ([DpapiSessionVault]).
 * - macOS: login Keychain via the `security` CLI ([KeychainSessionVault]).
 * - Linux: libsecret's `secret-tool` CLI when installed ([SecretToolSessionVault]).
 *
 * All impls are silent on failure (`save` reports via its return, `load` returns null,
 * `clear` is best-effort) and never log the payload. The transport encoding is always
 * base64 (newline-safe) so the payload survives argv/stdin/property-file round trips.
 */
interface SessionVault {
    /** Stores [payload], returning false (instead of throwing) when the vault is unusable. */
    fun save(payload: String): Boolean

    /** The stored payload, or null when absent/unreadable. */
    fun load(): String?

    /** Deletes the stored payload; best-effort, failures are swallowed. */
    fun clear()
}

/**
 * Picks the platform vault, or null when the host has none (the caller then falls back to
 * the plaintext 0600 `session.properties` file). JNA classes are touched only inside the
 * Windows impl's method bodies, so the macOS/Linux builds never load them.
 */
object SessionVaults {
    fun create(): SessionVault? = when {
        IsWindows -> DpapiSessionVault
        IsMacOs -> KeychainSessionVault()
        else -> SecretToolSessionVault.create()
    }
}

/**
 * Windows vault: `Crypt32Util.cryptProtectData` (DPAPI, per-user key) over the UTF-8
 * payload, base64-encoded into `~/.surgemusic/session.vault` — a text blob only the
 * current Windows account can decrypt (same trust scope as the 0600 POSIX file, but
 * machine-bound on top).
 */
private object DpapiSessionVault : SessionVault {
    private val vaultFile: Path = Path.of(System.getProperty("user.home"), DIR, VAULT_FILE)

    override fun save(payload: String): Boolean = runCatching {
        val blob = Crypt32Util.cryptProtectData(payload.toByteArray(Charsets.UTF_8))
        Files.createDirectories(vaultFile.parent)
        Files.writeString(vaultFile, Base64.getEncoder().encodeToString(blob))
        true
    }.getOrDefault(false)

    override fun load(): String? {
        if (!Files.exists(vaultFile)) return null
        return runCatching {
            val blob = Base64.getDecoder().decode(Files.readString(vaultFile).trim())
            String(Crypt32Util.cryptUnprotectData(blob), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun clear() {
        runCatching { Files.deleteIfExists(vaultFile) }
    }
}

/**
 * macOS vault: login Keychain through the `security` CLI —
 * save `security -q add-generic-password -s SurgeMedia -a session -w <b64> -U`,
 * load `security find-generic-password -s SurgeMedia -a session -w`,
 * clear `security delete-generic-password -s SurgeMedia -a session` (best-effort).
 * The payload is base64 first (a raw cookie header could carry shell/argv-hostile bytes)
 * and is never logged; every invocation is bounded by [exec]'s timeout so a hung keychain
 * access prompt cannot wedge the persist thread.
 *
 * [service]/[account] are injectable so tests can exercise the real CLI against a
 * dedicated entry instead of the app's `SurgeMedia`/`session` one.
 */
internal class KeychainSessionVault(
    private val service: String = KEYCHAIN_SERVICE,
    private val account: String = KEYCHAIN_ACCOUNT,
) : SessionVault {

    override fun save(payload: String): Boolean {
        val b64 = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return exec(SECURITY, "-q", "add-generic-password", "-s", service, "-a", account, "-w", b64, "-U") != null
    }

    override fun load(): String? {
        val out = exec(SECURITY, "find-generic-password", "-s", service, "-a", account, "-w") ?: return null
        return runCatching { String(Base64.getDecoder().decode(out.trim()), Charsets.UTF_8) }.getOrNull()
    }

    override fun clear() {
        exec(SECURITY, "-q", "delete-generic-password", "-s", service, "-a", account) // best-effort
    }
}

/**
 * Linux vault: libsecret's `secret-tool` CLI —
 * store `secret-tool store --label=SurgeMedia application surgemusic attr session`
 * (payload on stdin), lookup `secret-tool lookup application surgemusic attr session`,
 * clear `secret-tool clear application surgemusic attr session`. [create] returns null
 * when no `secret-tool` binary is on PATH (bare tiling-WM setups), leaving the caller on
 * the 0600-file fallback. The payload goes through base64 on stdin too: Properties text
 * is multi-line, and `lookup`'s trailing newline would otherwise be ambiguous with the
 * payload's own trailing newline on the round trip.
 */
internal object SecretToolSessionVault : SessionVault {

    /** Null unless a `secret-tool` binary is installed and executable. */
    fun create(): SessionVault? = if (onPath(SECRET_TOOL)) SecretToolSessionVault else null

    override fun save(payload: String): Boolean {
        val b64 = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return exec(SECRET_TOOL, "store", "--label=" + VAULT_LABEL, ATTR_1, ATTR_1_VALUE, ATTR_2, ATTR_2_VALUE, stdin = b64) != null
    }

    override fun load(): String? {
        val out = exec(SECRET_TOOL, "lookup", ATTR_1, ATTR_1_VALUE, ATTR_2, ATTR_2_VALUE) ?: return null
        return runCatching { String(Base64.getDecoder().decode(out.trim()), Charsets.UTF_8) }.getOrNull()
    }

    override fun clear() {
        exec(SECRET_TOOL, "clear", ATTR_1, ATTR_1_VALUE, ATTR_2, ATTR_2_VALUE) // best-effort
    }
}

// ---- shared CLI plumbing (payload-bearing output is never logged) ------------------------

/** `security` invocation bound to a CLI timeout so a hung keychain prompt cannot wedge us. */
private const val SECURITY = "security"

private const val SECRET_TOOL = "secret-tool"

/** Keychain entry of the app itself (tests use their own service name). */
private const val KEYCHAIN_SERVICE = "SurgeMedia"
private const val KEYCHAIN_ACCOUNT = "session"

/** secret-tool attribute pair + label identifying our single secret. */
private const val VAULT_LABEL = "SurgeMedia"
private const val ATTR_1 = "application"
private const val ATTR_1_VALUE = "surgemusic"
private const val ATTR_2 = "attr"
private const val ATTR_2_VALUE = "session"

private const val DIR = ".surgemusic"
private const val VAULT_FILE = "session.vault"

/** Upper bound per CLI invocation — generous; `security`/`secret-tool` finish in milliseconds. */
private const val CLI_TIMEOUT_SECONDS = 10L

/**
 * Runs [cmd], returning its stdout on exit 0 — null on spawn failure, non-zero exit or
 * timeout (the process is then killed). [stdin] (base64 payload, a few KB at most — well
 * under the OS pipe buffer) is written inline before waiting; stdout is captured and
 * stderr drained on daemon threads so `waitFor`'s timeout is the only blocking bound.
 * Nothing is ever logged — the output can carry the session payload.
 */
private fun exec(vararg cmd: String, stdin: String? = null): String? = runCatching {
    val proc = ProcessBuilder(*cmd).start()
    stdin?.let { data ->
        proc.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
    }
    val stdout = StringBuilder()
    val outPump = Thread {
        runCatching { proc.inputStream.bufferedReader().use { r -> r.forEachLine { stdout.appendLine(it) } } }
    }.apply { isDaemon = true; start() }
    val errDrain = Thread {
        runCatching { proc.errorStream.bufferedReader().use { it.readText() } }
    }.apply { isDaemon = true; start() }
    val finished = proc.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
        proc.destroyForcibly()
        return null
    }
    outPump.join(1_000)
    errDrain.join(1_000)
    if (proc.exitValue() != 0) return null
    stdout.toString()
}.getOrNull()

/** True when [binary] exists and is executable in some PATH entry. */
private fun onPath(binary: String): Boolean =
    System.getenv("PATH")?.split(File.pathSeparator)
        ?.any { it.isNotEmpty() && File(it, binary).canExecute() } == true
