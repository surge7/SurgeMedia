package com.surgemusic.app.downloads

import com.surgemusic.core.downloads.DownloadItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop twin of androidMain's `MediaStoreCleaner`: deletes finalized files under the
 * app's library roots — `~/Music/SurgeMedia/` (audio + cover art) and `~/Movies/SurgeMedia/`
 * (video), plus the pre-rebrand `~/Music/SurgeMusic/` root so files finalized by older
 * builds stay deletable. [deleteFor] removes this album's directory under every root,
 * [deleteAll] sweeps every child of every root. A sweep that matches nothing is still
 * [Result.success] with 0.
 */
object DesktopFileCleaner : DownloadFilesCleaner {

    /** (parent dir, brand dir) library roots the finalizer has written to, old and new. */
    private val roots: List<File> = listOf(
        "Music" to "SurgeMedia",
        "Movies" to "SurgeMedia",
        "Music" to "SurgeMusic", // pre-rebrand builds finalized audio here
    ).map { (parent, brand) ->
        File(System.getProperty("user.home"), "$parent/$brand")
    }

    override suspend fun deleteFor(item: DownloadItem): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val albumDir = sanitizeDirName(item.request.title).take(120)
            roots.sumOf { root -> deleteTree(root.resolve(albumDir)) }
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            roots.sumOf { root -> root.listFiles().orEmpty().sumOf { child -> deleteTree(child) } }
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

    // Same rule as androidMain's MediaFiles.sanitizeDirName / DesktopFinalizer.sanitize —
    // duplicated locally to avoid a cross-module dependency (DesktopFinalizer duplicates it too).
    private fun sanitizeDirName(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
