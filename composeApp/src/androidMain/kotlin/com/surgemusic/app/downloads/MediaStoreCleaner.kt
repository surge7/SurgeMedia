package com.surgemusic.app.downloads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import com.surgemusic.core.downloads.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes finalized download files from the public MediaStore roots the app writes to
 * (spec §6): `Music/SurgeMedia/<album>/…` on the Audio collection, cover art under
 * `Pictures/SurgeMedia/…` on Images, video under `Movies/SurgeMedia/…` on Video — all
 * on [MediaStore.VOLUME_EXTERNAL_PRIMARY]. The pre-rebrand `SurgeMusic` roots are
 * swept too, so files finalized by older builds stay deletable.
 *
 * Query strategy (deliberate): selections only ever bind FIXED `RELATIVE_PATH LIKE
 * '<root>/%'` prefixes — never a pattern built from the item title — and the album
 * match is a plain Kotlin [String.startsWith] on the fetched RELATIVE_PATH, so a
 * torrent title containing SQL LIKE wildcards (`%`, `_`) cannot widen what gets
 * deleted. Rows are then deleted one by one by `_id`.
 *
 * Ownership: items this app created are deletable directly on API 29+; each per-row
 * delete is wrapped in `runCatching` so a [SecurityException] (e.g. a
 * `RecoverableSecurityException` for a row owned by another app) only counts that row
 * as failed and the sweep continues. A sweep that deletes nothing is still
 * [Result.success] with 0 — only systemic failures (querying itself throwing) surface
 * as [Result.failure].
 */
class MediaStoreCleaner(private val context: Context) : DownloadFilesCleaner {

    /** (MediaStore collection, fixed relative-path root) pairs the finalizer writes to — new + legacy. */
    private val roots: List<Pair<Uri, String>> = listOf(
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to ROOT_AUDIO,
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to ROOT_IMAGES,
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to ROOT_VIDEO,
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to LEGACY_AUDIO,
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to LEGACY_IMAGES,
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to LEGACY_VIDEO,
    )

    override suspend fun deleteFor(item: DownloadItem): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            // Same album-dir rule as MediaStoreFinalizer — must stay in sync to find its rows.
            val albumDir = MediaFiles.sanitizeDirName(item.request.title).take(120)
            roots.sumOf { (collection, root) -> deleteMatching(collection, root, prefix = "$root$albumDir/") }
        }
    }

    override suspend fun deleteAll(): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            roots.sumOf { (collection, root) -> deleteMatching(collection, root, prefix = null) }
        }
    }

    /**
     * Queries [collection] for rows under the fixed [root] prefix, then deletes those
     * whose RELATIVE_PATH starts with [prefix] (null = everything under the root).
     * Returns the number of rows actually deleted. IDs are collected before deleting —
     * mutating rows while iterating the cursor is unsafe.
     */
    private fun deleteMatching(collection: Uri, root: String, prefix: String?): Int {
        val resolver = context.contentResolver
        val ids = queryIds(resolver, collection, root, prefix)
        var deleted = 0
        for (id in ids) {
            deleted += runCatching {
                resolver.delete(collection, "${BaseColumns._ID}=?", arrayOf(id.toString()))
            }.getOrDefault(0)
        }
        return deleted
    }

    private fun queryIds(
        resolver: ContentResolver,
        collection: Uri,
        root: String,
        prefix: String?,
    ): List<Long> {
        val cursor = resolver.query(
            collection,
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$root%"), // fixed prefix — see class KDoc; never built from user input
            null,
        ) ?: return emptyList() // collection not queryable here — nothing to delete from it
        val ids = ArrayList<Long>()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val path: String? = c.getString(pathCol)
                if (path != null && (prefix == null || path.startsWith(prefix))) {
                    ids.add(c.getLong(idCol))
                }
            }
        }
        return ids
    }

    private companion object {
        const val ROOT_AUDIO = "Music/SurgeMedia/"
        const val ROOT_IMAGES = "Pictures/SurgeMedia/"
        const val ROOT_VIDEO = "Movies/SurgeMedia/"
        const val LEGACY_AUDIO = "Music/SurgeMusic/" // pre-rebrand builds
        const val LEGACY_IMAGES = "Pictures/SurgeMusic/"
        const val LEGACY_VIDEO = "Movies/SurgeMusic/"
    }
}
