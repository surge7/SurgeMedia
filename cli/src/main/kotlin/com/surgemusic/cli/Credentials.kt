package com.surgemusic.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

data class Credentials(val username: String, val password: String)

/**
 * A rutracker session harvested from a logged-in browser: the raw Cookie header
 * (bb_session, cf_clearance, …) plus the User-Agent it was harvested with —
 * cf_clearance is bound to the UA, so both travel together.
 */
data class SessionConfig(val cookieHeader: String, val userAgent: String?)

/** group/other permission bits — the documented fix for these files is chmod 600. */
private val GROUP_AND_OTHER = setOf(
    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
)

/**
 * The cookies/credentials files carry a live session cookie or password, and the help
 * text documents chmod 600. Where the OS supports POSIX perms, warn loudly on stderr
 * if group/other have any access. Non-POSIX platforms (e.g. Windows: no such attribute
 * view) and unreadable attributes are silently skipped — a warning must never break the command.
 */
private fun warnIfInsecurePermissions(file: Path, what: String) {
    try {
        val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java) ?: return
        val perms = view.readAttributes().permissions()
        if (GROUP_AND_OTHER.any(perms::contains)) {
            System.err.println(
                "WARNING: $what file $file is accessible beyond its owner (group/other access set);\n" +
                    "         the secret in it is exposed to other local users. Fix: chmod 600 $file"
            )
        }
    } catch (_: UnsupportedOperationException) {
        // POSIX perms unsupported on this filesystem/OS — nothing to check
    } catch (_: java.io.IOException) {
        // attribute read failed — skip the warning
    }
}

fun loadCredentials(): Credentials? {
    val envUser = System.getenv("RUTRACKER_USER")
    val envPass = System.getenv("RUTRACKER_PASS")
    if (!envUser.isNullOrBlank() && !envPass.isNullOrBlank()) return Credentials(envUser, envPass)
    val file = Path.of(System.getProperty("user.home"), ".config", "surgemusic", "credentials")
    if (Files.isRegularFile(file)) {
        warnIfInsecurePermissions(file, what = "credentials")
        val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2) return Credentials(lines[0], lines[1])
    }
    return null
}

fun loadSessionConfig(): SessionConfig? {
    val envCookies = System.getenv("RUTRACKER_COOKIES")
    if (!envCookies.isNullOrBlank()) {
        return SessionConfig(envCookies, System.getenv("RUTRACKER_UA")?.takeIf { it.isNotBlank() })
    }
    val file = Path.of(System.getProperty("user.home"), ".config", "surgemusic", "cookies")
    if (Files.isRegularFile(file)) {
        warnIfInsecurePermissions(file, what = "cookies")
        val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty()) return SessionConfig(lines[0], lines.getOrNull(1))
    }
    return null
}
