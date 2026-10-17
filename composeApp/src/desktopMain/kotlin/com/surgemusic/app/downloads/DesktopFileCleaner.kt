package com.surgemusic.app.downloads

import com.surgemusic.core.downloads.DownloadItem
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop twin of androidMain's `MediaStoreCleaner`: deletes finalized files under
 * `~/Music/SurgeMusic/` — this album's directory for [deleteFor], every child of the
 * root for [deleteAll] (desktop keeps audio and cover art together under Music/, see
 * [DesktopFinalizer]). A sweep that matches nothing is still [Result.success] with 0.
 */
object DesktopFileCleaner : DownloadFilesCleaner {

    override suspend fun deleteFor(item: DownloadItem): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val albumDir = sanitizeDirName(item.request.title).take(120)
            deleteTree(musicRoot().resolve(albumDir).toFile())
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val root = musicRoot().toFile()
            root.listFiles().orEmpty().sumOf { child -> deleteTree(child) }
        }
    }

    /** walkBottomUp so children are removed before their directories; counts deleted files only. */
    private fun deleteTree(node: File): Int {
        if (!node.exists()) return 0
        var files = 0
        node.walkBottomUp().forEach { f ->
            if (f.isFile && f.delete()) {
                files++
            } else if (f.isDirectory) {
                f.delete() // best-effort: prune the (now empty) dir, node itself included
            }
        }
        return files
    }

    private fun musicRoot(): Path = Path.of(System.getProperty("user.home"), "Music", "SurgeMusic")

    // Same rule as androidMain's MediaFiles.sanitizeDirName / DesktopFinalizer.sanitize —
    // duplicated locally to avoid a cross-module dependency (DesktopFinalizer duplicates it too).
    private fun sanitizeDirName(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
