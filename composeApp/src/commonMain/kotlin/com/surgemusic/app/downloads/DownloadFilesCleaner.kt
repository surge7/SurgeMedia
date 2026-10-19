package com.surgemusic.app.downloads

import com.surgemusic.core.downloads.DownloadItem

/**
 * Platform seam for deleting the files a finished download finalized onto the device
 * (spec §6 roots): androidMain implements it over MediaStore ([MediaStoreCleaner]),
 * desktopMain over `~/Music/SurgeMedia` + `~/Movies/SurgeMedia` (`DesktopFileCleaner`).
 *
 * All methods return [Result] so the Downloads screen can banner the count or the
 * failure without exceptions crossing the boundary; a successful 0 means "nothing
 * matched to delete", not an error.
 */
interface DownloadFilesCleaner {

    /** Delete the finalized files of [item] (its album directory); returns the deleted file count. */
    suspend fun deleteFor(item: DownloadItem): Result<Int>

    /** Delete every file the app finalized under all of its roots; returns the deleted count. */
    suspend fun deleteAll(): Result<Int>
}

/** Test/preview stand-in that deletes nothing and reports 0. */
object NoopDownloadFilesCleaner : DownloadFilesCleaner {
    override suspend fun deleteFor(item: DownloadItem): Result<Int> = Result.success(0)
    override suspend fun deleteAll(): Result<Int> = Result.success(0)
}
