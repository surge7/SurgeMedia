package com.surgemusic.app.downloads

import com.surgemusic.app.VideoLibraryParent
import com.surgemusic.app.settings.DesktopSettingsStore
import com.surgemusic.core.downloads.DownloadItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop twin of androidMain's `MediaStoreCleaner`: deletes finalized files under the
 * app's library roots — the active music / video folders (Settings ▸ Storage; built-in
 * `~/Music/SurgeMedia/` and `~/Movies|Videos/SurgeMedia/` — macOS `Movies`,
 * Windows/Linux `Videos`) plus the pre-rebrand
 * `~/Music/SurgeMusic/` root so files finalized by older builds stay deletable.
 * [deleteFor] removes this album's directory under every root, [deleteAll] sweeps every
 * child of every root. A sweep that matches nothing is still [Result.success] with 0.
 */
class DesktopFileCleaner(private val settings: DesktopSettingsStore) : DownloadFilesCleaner {

    /** Library roots the finalizer has written to — custom or built-in, plus legacy. */
    private fun roots(): List<File> {
        val home = File(System.getProperty("user.home"))
        val builtinMusic = File(home, "Music/$BRAND_DIR")
        val builtinMovies = File(home, "$VideoLibraryParent/$BRAND_DIR")
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
            val albumDir = sanitizeAlbumName(item.request.title)
            // Final guard (sanitizeAlbumName already rejects these): a blank or dot
            // segment resolves against the root as the root itself — never recurse.
            require(albumDir.isNotBlank() && albumDir != "." && albumDir != "..") {
                "degenerate album dir for ${item.request.title}"
            }
            roots().sumOf { root -> deleteTree(containedChild(root, albumDir) ?: return@sumOf 0) }
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
            File(home, "$VideoLibraryParent/$BRAND_DIR"),
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

    /**
     * `root/<name>` only when the resolved path is provably [root]'s child — canonical
     * prefix check so a mangled name can never delete above the root (prefix equality
     * alone would let "SurgeMedia2" match "SurgeMedia"). Null when it is not.
     */
    private fun containedChild(root: File, name: String): File? {
        val child = root.resolve(name)
        val rootPath = root.canonicalPath + File.separator
        return if (child.canonicalPath.startsWith(rootPath)) child else null
    }

    private companion object {
        const val BRAND_DIR = "SurgeMedia"
        const val BRAND_DIR_LEGACY = "SurgeMusic"
    }
}
