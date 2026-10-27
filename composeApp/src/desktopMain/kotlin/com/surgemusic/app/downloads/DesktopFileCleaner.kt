package com.surgemusic.app.downloads

import com.surgemusic.app.settings.DesktopSettingsStore
import com.surgemusic.core.downloads.DownloadItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop twin of androidMain's `MediaStoreCleaner`: deletes finalized files under the
 * app's library roots — the active music / video folders (Settings ▸ Storage; built-in
 * `~/Music/SurgeMedia/` and `~/Movies/SurgeMedia/`) plus the pre-rebrand
 * `~/Music/SurgeMusic/` root so files finalized by older builds stay deletable.
 * [deleteFor] removes this album's directory under every root, [deleteAll] sweeps every
 * child of every root. A sweep that matches nothing is still [Result.success] with 0.
 */
class DesktopFileCleaner(private val settings: DesktopSettingsStore) : DownloadFilesCleaner {

    /** Library roots the finalizer has written to — custom or built-in, plus legacy. */
    private fun roots(): List<File> {
        val home = File(System.getProperty("user.home"))
        val builtinMusic = File(home, "Music/$BRAND_DIR")
        val builtinMovies = File(home, "Movies/$BRAND_DIR")
        val music = settings.musicFolder.value.ifBlank { builtinMusic.absolutePath }
        val movies = settings.videoFolder.value.ifBlank { builtinMovies.absolutePath }
        return buildList {
            add(File(music))
            add(File(movies))
            // Keep the built-ins deletable even after a custom folder is chosen: files
            // finalized before the switch still live there.
            if (File(music).absolutePath != builtinMusic.absolutePath) add(builtinMusic)
            if (File(movies).absolutePath != builtinMovies.absolutePath) add(builtinMovies)
            add(File(home, "Music/$BRAND_DIR_LEGACY")) // pre-rebrand builds
        }
    }

    override suspend fun deleteFor(item: DownloadItem): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val albumDir = sanitizeDirName(item.request.title).take(120)
            roots().sumOf { root -> deleteTree(root.resolve(albumDir)) }
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            // App-owned roots only — a custom folder may hold the user's pre-existing
            // media; per-item deletes ([deleteFor], title-scoped) cover it.
            sweepRoots().sumOf { root -> root.listFiles().orEmpty().sumOf { child -> deleteTree(child) } }
        }
    }

    /** The roots [deleteAll] may sweep: the built-ins + legacy, never custom folders. */
    private fun sweepRoots(): List<File> {
        val home = File(System.getProperty("user.home"))
        return listOf(
            File(home, "Music/$BRAND_DIR"),
            File(home, "Movies/$BRAND_DIR"),
            File(home, "Music/$BRAND_DIR_LEGACY"),
        )
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
    private fun sanitizeDirName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private companion object {
        const val BRAND_DIR = "SurgeMedia"
        const val BRAND_DIR_LEGACY = "SurgeMusic"
    }
}
