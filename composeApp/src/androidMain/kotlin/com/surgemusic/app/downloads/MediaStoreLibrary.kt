package com.surgemusic.app.downloads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.surgemusic.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure grouping logic of the Downloaded tab, extracted so plain JVM unit tests can drive
 * it without Android framework classes (same pattern as [MediaFiles]).
 *
 * Paths are MediaStore `RELATIVE_PATH` values like `Music/SurgeMedia/<album>/CD1/01.flac`.
 */
internal object LibraryGrouping {

    /**
     * The album (release) directory of [relativePath] under [rootPrefix], or null when the
     * path is outside the root. Files directly at the root (empty album edge) bucket to
     * [DownloadedRelease.UNTITLED].
     */
    fun albumOf(relativePath: String, rootPrefix: String): String? {
        if (!relativePath.startsWith(rootPrefix)) return null
        val rest = relativePath.removePrefix(rootPrefix)
        val album = rest.substringBefore('/').trim()
        return album.ifEmpty { DownloadedRelease.UNTITLED }
    }

    /** Aggregates (relativePath, size) rows of one root into per-release [DownloadedRelease]s. */
    fun buildReleases(
        rootId: String,
        kindLabel: String,
        locationLabel: String,
        rootPrefix: String,
        rows: List<Pair<String, Long>>,
    ): List<DownloadedRelease> {
        val acc = linkedMapOf<String, Pair<Int, Long>>()
        rows.forEach { (path, size) ->
            val album = albumOf(path, rootPrefix) ?: return@forEach
            val (count, bytes) = acc[album] ?: (0 to 0L)
            acc[album] = (count + 1) to (bytes + size)
        }
        return acc.map { (title, agg) ->
            DownloadedRelease(rootId, title, locationLabel, kindLabel, agg.first, agg.second)
        }
    }

    /**
     * Whether [relativePath] belongs to [album] under [rootPrefix] — the delete filter.
     * UNTITLED matches only files sitting directly at the root (no `/` after the prefix);
     * a normal album matches exactly its `album/` subtree, never a sibling with a shared
     * title prefix.
     */
    fun matchesRelease(relativePath: String, rootPrefix: String, album: String): Boolean {
        if (!relativePath.startsWith(rootPrefix)) return false
        val rest = relativePath.removePrefix(rootPrefix)
        return if (album == DownloadedRelease.UNTITLED) {
            !rest.contains('/')
        } else {
            rest.startsWith("$album/")
        }
    }
}

/**
 * Downloaded tab over MediaStore (androidMain twin of desktopMain's `DesktopLibrary`):
 * enumerates and deletes what the app finalized under its library roots — the built-in
 * `Music|Movies|Pictures/SurgeMedia/…` plus the pre-rebrand `SurgeMusic` roots on
 * [MediaStore.VOLUME_EXTERNAL_PRIMARY], and, when set (Settings ▸ Storage), the custom
 * music/video folders: a folder that maps onto a MediaStore standard root is queried
 * with the same RELATIVE_PATH machinery; any other SAF tree is listed via
 * [DocumentFile]. Cover art always stays on the default Pictures root.
 *
 * Query strategy mirrors [MediaStoreCleaner]: selections bind only FIXED
 * `RELATIVE_PATH LIKE '<root>/%'` prefixes (never a pattern built from the release
 * title), and per-release matching is a plain Kotlin [String] check, so a title
 * containing SQL LIKE wildcards (`%`, `_`) cannot widen what gets listed or deleted.
 * Per-row deletes are wrapped in `runCatching` (a SecurityException on a row this app
 * does not own only counts that row as failed); a sweep that matches nothing is
 * [Result.success] with 0.
 *
 * [deleteAll] deliberately sweeps only the app's own built-in + legacy roots — never a
 * custom folder, which may hold the user's pre-existing media the app cannot attribute.
 * Custom-folder content is removed per release ([delete]) or per download item
 * ([MediaStoreCleaner.deleteFor]), both scoped to one title-derived album directory.
 */
class MediaStoreLibrary(
    private val context: Context,
    private val settings: SettingsStore,
) : DownloadedLibrary {

    /** One library root: a MediaStore collection plus its fixed relative-path prefix. */
    private data class Root(
        val id: String,
        val collection: Uri,
        val prefix: String,
        val kindLabel: String,
        val locationLabel: String,
    )

    /** One SAF tree root (custom folder with no MediaStore equivalent). */
    private data class TreeRoot(
        val id: String,
        val treeUri: Uri,
        val kindLabel: String,
        val locationLabel: String,
    )

    private val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY

    private fun audioCollection() = MediaStore.Audio.Media.getContentUri(volume)
    private fun videoCollection() = MediaStore.Video.Media.getContentUri(volume)

    /** Built-in + legacy roots — also the only roots [deleteAll] ever sweeps. */
    private val builtinRoots: List<Root> = listOf(
        Root("music", audioCollection(), "Music/SurgeMedia/", "Music", "Music"),
        Root("movies", videoCollection(), "Movies/SurgeMedia/", "Movies", "Movies"),
        Root("pictures", MediaStore.Images.Media.getContentUri(volume), "Pictures/SurgeMedia/", "Pictures", "Pictures"),
        Root("music_legacy", audioCollection(), "Music/SurgeMusic/", "Music", "Music (legacy)"),
        Root("movies_legacy", videoCollection(), "Movies/SurgeMusic/", "Movies", "Movies (legacy)"),
        Root("pictures_legacy", MediaStore.Images.Media.getContentUri(volume), "Pictures/SurgeMusic/", "Pictures", "Pictures (legacy)"),
    )

    /** Custom-folder MediaStore root for one kind, or null when unset/unmappable. */
    private fun customRoot(kind: String, folder: String): Root? {
        if (folder.isBlank()) return null
        val docId = MediaFiles.treeDocumentIdOf(folder) ?: return null
        val prefix = MediaFiles.mediaStorePrefixFor(kind, docId) ?: return null
        val collection = if (kind == MediaFiles.KIND_VIDEO) videoCollection() else audioCollection()
        return Root(
            id = if (kind == MediaFiles.KIND_VIDEO) "movies_custom" else "music_custom",
            collection = collection,
            prefix = prefix,
            kindLabel = if (kind == MediaFiles.KIND_VIDEO) "Movies" else "Music",
            locationLabel = prefix.trimEnd('/'),
        )
    }

    /** Custom-folder SAF tree for one kind, or null when unset or mapped onto MediaStore. */
    private fun customTree(kind: String, folder: String): TreeRoot? {
        if (folder.isBlank()) return null
        val docId = MediaFiles.treeDocumentIdOf(folder) ?: return null
        if (MediaFiles.mediaStorePrefixFor(kind, docId) != null) return null
        return TreeRoot(
            id = if (kind == MediaFiles.KIND_VIDEO) "movies_custom" else "music_custom",
            treeUri = Uri.parse(folder),
            kindLabel = if (kind == MediaFiles.KIND_VIDEO) "Movies" else "Music",
            locationLabel = docId,
        )
    }

    private fun allRoots(): List<Root> = builtinRoots + listOfNotNull(
        customRoot(MediaFiles.KIND_AUDIO, settings.musicFolder.value),
        customRoot(MediaFiles.KIND_VIDEO, settings.videoFolder.value),
    )

    private fun allTrees(): List<TreeRoot> = listOfNotNull(
        customTree(MediaFiles.KIND_AUDIO, settings.musicFolder.value),
        customTree(MediaFiles.KIND_VIDEO, settings.videoFolder.value),
    )

    override suspend fun list(): Result<List<DownloadedRelease>> = runCatching {
        withContext(Dispatchers.IO) {
            val storeReleases = allRoots().flatMap { root ->
                LibraryGrouping.buildReleases(
                    rootId = root.id,
                    kindLabel = root.kindLabel,
                    locationLabel = root.locationLabel,
                    rootPrefix = root.prefix,
                    rows = queryPaths(root).map { it.first to it.second },
                )
            }
            val treeReleases = allTrees().flatMap { tree ->
                LibraryGrouping.buildReleases(
                    rootId = tree.id,
                    kindLabel = tree.kindLabel,
                    locationLabel = tree.locationLabel,
                    rootPrefix = "",
                    rows = treeRows(tree),
                )
            }
            (storeReleases + treeReleases)
                .sortedWith(compareBy({ it.title.lowercase() }, { it.rootId }))
        }
    }

    override suspend fun delete(release: DownloadedRelease): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val root = allRoots().firstOrNull { it.id == release.rootId }
            if (root != null) {
                deleteMatching(root) { path ->
                    LibraryGrouping.matchesRelease(path, root.prefix, release.title)
                }
            } else {
                val tree = allTrees().firstOrNull { it.id == release.rootId }
                    ?: error("unknown library root ${release.rootId}")
                deleteFromTree(tree, release.title)
            }
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            // Built-in + legacy roots only — see class KDoc (custom folders are never swept).
            builtinRoots.sumOf { root -> deleteMatching(root) { true } }
        }
    }

    /** ("<album>/sub/file", size) rows under [tree], synthesized from the DocumentFile walk. */
    private fun treeRows(tree: TreeRoot): List<Pair<String, Long>> {
        val root = DocumentFile.fromTreeUri(context, tree.treeUri) ?: return emptyList()
        val rows = mutableListOf<Pair<String, Long>>()
        fun walk(dir: DocumentFile, prefix: String) {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                when {
                    child.isDirectory -> walk(child, "$prefix$name/")
                    child.isFile -> rows += "$prefix$name" to child.length()
                }
            }
        }
        walk(root, "")
        return rows
    }

    /** Deletes one release's subtree (or its loose files for UNTITLED) inside [tree]. */
    private fun deleteFromTree(tree: TreeRoot, title: String): Int {
        val root = DocumentFile.fromTreeUri(context, tree.treeUri) ?: return 0
        if (title == DownloadedRelease.UNTITLED) {
            return root.listFiles().filter { !it.isDirectory }.count { it.delete() }
        }
        val album = root.listFiles().firstOrNull { it.isDirectory && it.name == title } ?: return 0
        return deleteTreeDoc(album)
    }

    /** Depth-first delete; counts removed files, prunes directories after their children. */
    private fun deleteTreeDoc(node: DocumentFile): Int {
        if (node.isDirectory) {
            var files = 0
            node.listFiles().forEach { files += deleteTreeDoc(it) }
            node.delete()
            return files
        }
        return if (node.isFile && node.delete()) 1 else 0
    }

    /** (RELATIVE_PATH, SIZE) of every row under [root]'s fixed prefix; empty when not queryable. */
    private fun queryPaths(root: Root): List<Pair<String, Long>> {
        val cursor = context.contentResolver.query(
            root.collection,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${root.prefix}%"), // fixed prefix — see class KDoc; never built from user input
            null,
        ) ?: return emptyList()
        val rows = ArrayList<Pair<String, Long>>()
        cursor.use { c ->
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val path: String? = c.getString(pathCol)
                if (path != null && path.startsWith(root.prefix)) {
                    rows += path to (c.getLong(sizeCol).coerceAtLeast(0L))
                }
            }
        }
        return rows
    }

    /**
     * Deletes the rows of [root] whose RELATIVE_PATH satisfies [filter] (already
     * prefix-checked); returns the number actually deleted. IDs are collected before
     * deleting — mutating rows while iterating the cursor is unsafe.
     */
    private fun deleteMatching(root: Root, filter: (String) -> Boolean): Int {
        val resolver: ContentResolver = context.contentResolver
        val cursor = resolver.query(
            root.collection,
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${root.prefix}%"),
            null,
        ) ?: return 0
        val ids = ArrayList<Long>()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val path: String? = c.getString(pathCol)
                if (path != null && filter(path)) ids += c.getLong(idCol)
            }
        }
        var deleted = 0
        for (id in ids) {
            deleted += runCatching {
                resolver.delete(root.collection, "${BaseColumns._ID}=?", arrayOf(id.toString()))
            }.getOrDefault(0)
        }
        return deleted
    }
}
