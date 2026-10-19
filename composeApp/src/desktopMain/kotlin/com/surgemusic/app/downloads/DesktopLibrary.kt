package com.surgemusic.app.downloads

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloaded tab on desktop (desktopMain twin of androidMain's `MediaStoreLibrary`):
 * enumerates and deletes what [DesktopFinalizer] moved into the user's libraries —
 * `~/Music/SurgeMedia/` (audio + cover art) and `~/Movies/SurgeMedia/` (video), plus the
 * pre-rebrand `~/Music/SurgeMusic/` root. Files directly under a root (empty-title edge)
 * are grouped under [DownloadedRelease.UNTITLED]. A scan that finds nothing is
 * [Result.success] with an empty list.
 */
object DesktopLibrary : DownloadedLibrary {

    /** (rootId, directory, kindLabel) — the roots the finalizer writes to, under [home]. */
    private fun roots(home: Path = Path.of(System.getProperty("user.home"))): List<Triple<String, Path, String>> =
        listOf(
            Triple("music", home.resolve("Music/SurgeMedia"), "Music"),
            Triple("movies", home.resolve("Movies/SurgeMedia"), "Movies"),
            Triple("music_legacy", home.resolve("Music/SurgeMusic"), "Music"),
        )

    private fun labels(): Map<String, String> = mapOf(
        "music" to "~/Music/SurgeMedia",
        "movies" to "~/Movies/SurgeMedia",
        "music_legacy" to "~/Music/SurgeMusic (legacy)",
    )

    override suspend fun list(): Result<List<DownloadedRelease>> = runCatching {
        withContext(Dispatchers.IO) {
            roots().flatMap { (rootId, root, kindLabel) ->
                entriesUnder(root.toFile()).map { (name, dir) ->
                    val files = dir.walkTopDown().filter { it.isFile }.toList()
                    DownloadedRelease(
                        rootId = rootId,
                        title = name,
                        locationLabel = labels().getValue(rootId),
                        kindLabel = kindLabel,
                        filesCount = files.size,
                        totalBytes = files.sumOf { it.length() },
                    )
                }
            }.sortedWith(compareBy({ it.title.lowercase() }, { it.rootId }))
        }
    }

    override suspend fun delete(release: DownloadedRelease): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val root = roots().firstOrNull { it.first == release.rootId }?.second
                ?: error("unknown library root ${release.rootId}")
            if (release.title == DownloadedRelease.UNTITLED) {
                // Loose files directly at the root — delete only them, never sibling albums.
                root.toFile().listFiles().orEmpty().filter { it.isFile }.count { it.delete() }
            } else {
                deleteTree(root.resolve(release.title).toFile())
            }
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            roots().sumOf { (_, root, _) ->
                root.toFile().listFiles().orEmpty().sumOf { child -> deleteTree(child) }
            }
        }
    }

    /** (display name, dir) of one release per child of [root]; loose files bucket to UNTITLED. */
    private fun entriesUnder(root: File): List<Pair<String, File>> {
        if (!root.isDirectory) return emptyList()
        val entries = ArrayList<Pair<String, File>>()
        var loose = false
        root.listFiles()?.sortedBy { it.name }?.forEach { child ->
            if (child.isDirectory) {
                entries += child.name to child
            } else {
                loose = true
            }
        }
        if (loose) entries += DownloadedRelease.UNTITLED to root
        return entries
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
}
