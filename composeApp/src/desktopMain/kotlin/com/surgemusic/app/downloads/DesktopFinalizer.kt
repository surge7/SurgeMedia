package com.surgemusic.app.downloads

import com.surgemusic.core.downloads.DownloadItem
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop twin of androidMain's `MediaStoreFinalizer` (plan Task 6): moves a finished
 * torrent's staged files into `~/Music/SurgeMusic/<sanitized title>/…` with `Files.move`.
 *
 * Contract mirrors MediaStoreFinalizer: media files only (audio + cover art; non-media
 * like .log/.cue/.nfo are skipped by design), per-file isolation so one rejected file
 * cannot fail the batch, the staging tree is deleted afterwards, and an all-skipped batch
 * fails the finalize with the skipped list. Cover art stays under Music/ here —
 * androidMain's `Pictures/SurgeMusic` split exists only because MediaStore enforces
 * collection-specific roots, which desktop has no equivalent of.
 *
 * Moves use `ATOMIC_MOVE`; on [AtomicMoveNotSupportedException] (staging and home on
 * different volumes) it falls back to copy+delete. The album directory is created lazily,
 * by the first moved file's parent-dir creation, so an all-skipped batch leaves no empty
 * `~/Music/SurgeMusic/<album>` behind (android MediaStore creates nothing until the first
 * insert either). Runs off the main thread; any failure surfaces as `Result.failure` for
 * DownloadManager to render on the item.
 */
object DesktopFinalizer {

    /** Same extension sets as androidMain's `MediaFiles` — kept in sync (it is androidModule-internal). */
    private val audioExt = setOf("flac", "mp3", "ogg", "oga", "wav", "m4a", "aac", "ape", "wv", "opus")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp")

    suspend fun finalize(item: DownloadItem, staging: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stagingRoot = File(staging) // …/staging/<topicId>/<torrentName>/files…
                val torrentDir = stagingRoot.listFiles()?.firstOrNull { it.isDirectory } ?: stagingRoot
                val albumDir = sanitize(item.request.title).take(120)
                // No eager createDirectories: the first successful move creates the album
                // tree via moveIntoMusic's parent-dir creation (all-skipped → no empty dir).
                val musicRoot = musicRoot().resolve(albumDir)
                var moved = 0
                val failedFiles = mutableListOf<String>()
                torrentDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .forEach { f ->
                        if (classify(f.name) == null) return@forEach
                        // Per-file isolation: one unmovable file must not fail the whole batch.
                        val ok = runCatching {
                            moveIntoMusic(f, torrentDir, musicRoot)
                        }.isSuccess
                        if (ok) moved++ else failedFiles.add(f.name)
                    }
                stagingRoot.deleteRecursively()
                require(moved > 0) {
                    "no files finalized${if (failedFiles.isNotEmpty()) "; failed: $failedFiles" else ""}"
                }
                buildString {
                    append("$moved files → ~/Music/SurgeMusic/$albumDir")
                    if (failedFiles.isNotEmpty()) append(" (skipped: ${failedFiles.joinToString()})")
                }
            }
        }

    /** Preserves the torrent's internal sub-path under the album dir. */
    private fun moveIntoMusic(file: File, torrentDir: File, musicRoot: Path) {
        val sub = file.parentFile?.toRelativeString(torrentDir).orEmpty()
        val target = if (sub.isEmpty()) {
            musicRoot.resolve(file.name)
        } else {
            musicRoot.resolve(sub).resolve(file.name)
        }
        Files.createDirectories(target.parent)
        if (Files.exists(target)) Files.delete(target)
        try {
            Files.move(file.toPath(), target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            Files.delete(file.toPath())
        }
    }

    /** (non-null) when the file is media and should be moved; null to skip (cue/log/nfo/…). */
    private fun classify(name: String): Boolean? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in audioExt || ext in imageExt -> true
            else -> null
        }
    }

    private fun musicRoot(): Path = Path.of(System.getProperty("user.home"), "Music", "SurgeMusic")

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
