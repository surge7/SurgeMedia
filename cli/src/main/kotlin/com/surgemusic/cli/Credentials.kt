package com.surgemusic.cli

import java.nio.file.Files
import java.nio.file.Path

data class Credentials(val username: String, val password: String)

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
