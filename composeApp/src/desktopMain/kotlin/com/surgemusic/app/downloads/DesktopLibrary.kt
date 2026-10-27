package com.surgemusic.app.downloads

import com.surgemusic.app.settings.DesktopSettingsStore
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloaded tab on desktop (desktopMain twin of androidMain's `MediaStoreLibrary`):
 * enumerates and deletes what [DesktopFinalizer] moved into the user's libraries — the
 * active music / video folders (Settings ▸ Storage; built-in `~/Music/SurgeMedia` and
 * `~/Movies/SurgeMedia`), plus the pre-rebrand `~/Music/SurgeMusic/` root so files from
 * older builds stay visible and deletable. Files directly under a root (empty-title
 * edge) are grouped under [DownloadedRelease.UNTITLED]. A scan that finds nothing is
 * [Result.success] with an empty list.
 */
class DesktopLibrary(private val settings: DesktopSettingsStore) : DownloadedLibrary {

    /** (rootId, directory, kindLabel) — the roots the finalizer writes to, under [home]. */
    private fun roots(home: Path = Path.of(System.getProperty("user.home"))): List<Triple<String, Path, String>> {
        val music = customOr(settings.musicFolder.value, home.resolve("Music/$BRAND_DIR"))
        val movies = customOr(settings.videoFolder.value, home.resolve("Movies/$BRAND_DIR"))
        return buildList {
            if (music != movies) {
                add(Triple("music", music, "Music"))
                add(Triple("movies", movies, "Movies"))
            } else {
                // Both kinds share one folder — one entry covers it (kind "Music" as the majority kind).
                add(Triple("music", music, "Music"))
            }
            // Older builds wrote under the built-in roots; keep them listed even after a
            // custom folder is chosen, so their files remain visible and deletable.
            if (music.toString() != home.resolve("Music/$BRAND_DIR").toString()) {
                add(Triple("music_builtin", home.resolve("Music/$BRAND_DIR"), "Music"))
            }
            if (movies.toString() != home.resolve("Movies/$BRAND_DIR").toString()) {
                add(Triple("movies_builtin", home.resolve("Movies/$BRAND_DIR"), "Movies"))
            }
            add(Triple("music_legacy", home.resolve("Music/$BRAND_DIR_LEGACY"), "Music"))
        }
    }

    private fun customOr(custom: String, default: Path): Path =
        if (custom.isBlank()) default else Path.of(custom)

    private fun labels(): Map<String, String> =
        roots().associate { (id, root, _) ->
            id to (display(root) + if (id.endsWith("_legacy")) " (legacy)" else "")
        }

    /** Shortens the home prefix to `~` so labels stay readable. */
    private fun display(path: Path): String =
        path.toAbsolutePath().toString().replace(System.getProperty("user.home"), "~")

    override suspend fun list(): Result<List<DownloadedRelease>> = runCatching {
        withContext(Dispatchers.IO) {
            val labels = labels()
            roots().flatMap { (rootId, root, kindLabel) ->
                entriesUnder(root.toFile()).map { (name, dir) ->
                    val files = dir.walkTopDown().filter { it.isFile }.toList()
                    DownloadedRelease(
                        rootId = rootId,
                        title = name,
                        locationLabel = labels.getValue(rootId),
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
            // App-owned roots only — a custom folder may hold the user's pre-existing
            // media the app cannot attribute; its content is removed per release ([delete]).
            sweepRoots().sumOf { root ->
                root.toFile().listFiles().orEmpty().sumOf { child -> deleteTree(child) }
            }
        }
    }

    /** The roots [deleteAll] may sweep: the built-ins + legacy, never custom folders. */
    private fun sweepRoots(): List<Path> {
        val home = Path.of(System.getProperty("user.home"))
        return listOf(
            home.resolve("Music/$BRAND_DIR"),
            home.resolve("Movies/$BRAND_DIR"),
            home.resolve("Music/$BRAND_DIR_LEGACY"),
        )
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

    private companion object {
        const val BRAND_DIR = "SurgeMedia"
        const val BRAND_DIR_LEGACY = "SurgeMusic"
    }
}
