package com.surgemusic.app.downloads

/**
 * One finalized release on the device's media libraries (Downloaded tab): every file the
 * app finalized under `<library root>/<sanitized title>/…`, aggregated.
 *
 * [rootId] is the stable identifier of the library root it lives under (see the platform
 * `DownloadedLibrary` implementations); [title] is the album-directory name — the
 * sanitized torrent title. Files finalized directly at a root (empty title edge) are
 * grouped under [UNTITLED].
 */
data class DownloadedRelease(
    val rootId: String,
    val title: String,
    val locationLabel: String,
    val kindLabel: String,
    val filesCount: Int,
    val totalBytes: Long,
) {
    companion object {
        /** Grouping bucket for files the finalizer wrote directly at a library root. */
        const val UNTITLED = "Unsorted"
    }
}

/**
 * Platform seam for the Downloaded tab: what the app has already finalized onto the
 * device, independent of the transfer history ([core DownloadManager] items are
 * in-memory and vanish on restart — this is the always-available control surface).
 *
 * All methods return [Result] so the screen can banner the count or the failure without
 * exceptions crossing the boundary; a successful 0 means "nothing found", not an error.
 */
interface DownloadedLibrary {

    /** Every release under every root (new `SurgeMedia` + legacy `SurgeMusic`), sorted by title. */
    suspend fun list(): Result<List<DownloadedRelease>>

    /** Deletes [release]'s files under its root; returns the deleted file count. */
    suspend fun delete(release: DownloadedRelease): Result<Int>

    /** Deletes every file under all roots; returns the deleted file count. */
    suspend fun deleteAll(): Result<Int>
}
