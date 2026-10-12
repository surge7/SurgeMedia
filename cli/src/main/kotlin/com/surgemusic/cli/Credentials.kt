package com.surgemusic.cli

import java.nio.file.Files
import java.nio.file.Path

data class Credentials(val username: String, val password: String)

/**
 * A rutracker session harvested from a logged-in browser: the raw Cookie header
 * (bb_session, cf_clearance, …) plus the User-Agent it was harvested with —
 * cf_clearance is bound to the UA, so both travel together.
 */
data class SessionConfig(val cookieHeader: String, val userAgent: String?)

fun loadCredentials(): Credentials? {
    val envUser = System.getenv("RUTRACKER_USER")
    val envPass = System.getenv("RUTRACKER_PASS")
    if (!envUser.isNullOrBlank() && !envPass.isNullOrBlank()) return Credentials(envUser, envPass)
    val file = Path.of(System.getProperty("user.home"), ".config", "surgemusic", "credentials")
    if (Files.isRegularFile(file)) {
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
        val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty()) return SessionConfig(lines[0], lines.getOrNull(1))
    }
    return null
}
