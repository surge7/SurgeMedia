package com.surgemusic.app.downloads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
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
 * enumerates and deletes what the app finalized under its public library roots —
 * `Music|Movies|Pictures/SurgeMedia/…` plus the pre-rebrand `SurgeMusic` roots — on
 * [MediaStore.VOLUME_EXTERNAL_PRIMARY].
 *
 * Query strategy mirrors [MediaStoreCleaner]: selections bind only FIXED
 * `RELATIVE_PATH LIKE '<root>/%'` prefixes (never a pattern built from the release
 * title), and per-release matching is a plain Kotlin [String] check, so a title
 * containing SQL LIKE wildcards (`%`, `_`) cannot widen what gets listed or deleted.
 * Per-row deletes are wrapped in `runCatching` (a SecurityException on a row this app
 * does not own only counts that row as failed); a sweep that matches nothing is
 * [Result.success] with 0.
 */
class MediaStoreLibrary(private val context: Context) : DownloadedLibrary {

    /** One library root: a MediaStore collection plus its fixed relative-path prefix. */
    private data class Root(
        val id: String,
        val collection: Uri,
        val prefix: String,
        val kindLabel: String,
        val locationLabel: String,
    )

    private val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY

    private val roots: List<Root> = listOf(
        Root("music", MediaStore.Audio.Media.getContentUri(volume), "Music/SurgeMedia/", "Music", "Music"),
        Root("movies", MediaStore.Video.Media.getContentUri(volume), "Movies/SurgeMedia/", "Movies", "Movies"),
        Root("pictures", MediaStore.Images.Media.getContentUri(volume), "Pictures/SurgeMedia/", "Pictures", "Pictures"),
        Root("music_legacy", MediaStore.Audio.Media.getContentUri(volume), "Music/SurgeMusic/", "Music", "Music (legacy)"),
        Root("movies_legacy", MediaStore.Video.Media.getContentUri(volume), "Movies/SurgeMusic/", "Movies", "Movies (legacy)"),
        Root("pictures_legacy", MediaStore.Images.Media.getContentUri(volume), "Pictures/SurgeMusic/", "Pictures", "Pictures (legacy)"),
    )

    override suspend fun list(): Result<List<DownloadedRelease>> = runCatching {
        withContext(Dispatchers.IO) {
            roots.flatMap { root ->
                LibraryGrouping.buildReleases(
                    rootId = root.id,
                    kindLabel = root.kindLabel,
                    locationLabel = root.locationLabel,
                    rootPrefix = root.prefix,
                    rows = queryPaths(root).map { it.first to it.second },
                )
            }.sortedWith(compareBy({ it.title.lowercase() }, { it.rootId }))
        }
    }

    override suspend fun delete(release: DownloadedRelease): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            val root = roots.firstOrNull { it.id == release.rootId }
                ?: error("unknown library root ${release.rootId}")
            deleteMatching(root) { path ->
                LibraryGrouping.matchesRelease(path, root.prefix, release.title)
            }
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            roots.sumOf { root -> deleteMatching(root) { true } }
        }
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
