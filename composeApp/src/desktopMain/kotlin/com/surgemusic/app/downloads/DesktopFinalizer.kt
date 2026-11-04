package com.surgemusic.app.downloads

import com.surgemusic.app.VideoLibraryParent
import com.surgemusic.app.settings.DesktopSettingsStore
import com.surgemusic.app.settings.SettingKeys
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
 * torrent's staged media files into the user's library — audio + cover art under the
 * music folder, video under the video folder (Settings ▸ Storage; built-in defaults
 * `~/Music/SurgeMedia/<title>/…` and `~/Movies|Videos/SurgeMedia/…` — macOS uses
 * `Movies`, Windows/Linux `Videos`) — with `Files.move`.
 * (Files finalized by pre-rebrand builds live under `~/Music/SurgeMusic`;
 * [DesktopFileCleaner] sweeps that root too.)
 *
 * Contract mirrors MediaStoreFinalizer: media files only (non-media like .log/.cue/.nfo
 * are skipped by design), per-file isolation so one rejected file cannot fail the batch,
 * the staging tree is deleted only once every file has succeeded — a failed file stays
 * in staging for retry salvage, and an all-skipped batch fails the finalize with the
 * per-file causes. An existing destination file is auto-renamed `name (1).ext`
 * (MediaStore-style) — a user's file is never overwritten. Cover art stays under the
 * music folder — androidMain's `Pictures/SurgeMedia` split exists only because
 * MediaStore enforces collection-specific roots, which desktop has no equivalent of.
 *
 * Moves use `ATOMIC_MOVE`; on [AtomicMoveNotSupportedException] (staging and target on
 * different volumes) it falls back to copy+delete. The album directory is created lazily,
 * by the first moved file's parent-dir creation, so an all-skipped batch leaves no empty
 * library dir behind. Runs off the main thread; any failure surfaces as
 * `Result.failure` for DownloadManager to render on the item.
 */
class DesktopFinalizer(private val settings: DesktopSettingsStore) {

    /** Same extension sets as androidMain's `MediaFiles` — kept in sync (it is androidModule-internal). */
    private val audioExt = setOf("flac", "mp3", "ogg", "oga", "wav", "m4a", "aac", "ape", "wv", "opus")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp")
    private val videoExt = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mpg", "mpeg", "vob", "wmv", "flv", "3gp",
    )

    /** Destination root for finished audio (+cover art); the custom folder or the built-in default. */
    private fun musicRoot(): Path = root(settings.musicFolder.value, "Music")

    /** Destination root for finished video; the custom folder or the built-in default. */
    private fun videoRoot(): Path = root(settings.videoFolder.value, VideoLibraryParent)

    private fun root(custom: String, defaultParent: String): Path =
        if (custom.isBlank()) {
            Path.of(System.getProperty("user.home"), defaultParent, SettingKeys.BRAND_DIR)
        } else {
            Path.of(custom)
        }

    suspend fun finalize(item: DownloadItem, staging: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stagingRoot = File(staging) // …/staging/<topicId>/<torrentName>/files…
                val torrentDir = stagingRoot.listFiles()?.firstOrNull { it.isDirectory } ?: stagingRoot
                val albumDir = sanitizeAlbumName(item.request.title)
                // No eager createDirectories: the first successful move creates the album
                // tree via moveIntoLibrary's parent-dir creation (all-skipped → no empty dir).
                val musicRoot = musicRoot().resolve(albumDir)
                val moviesRoot = videoRoot().resolve(albumDir)
                var moved = 0
                var renamed = 0
                val failedFiles = mutableListOf<String>() // "name: cause" details
                val rootsWritten = linkedSetOf<String>()
                torrentDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .forEach { f ->
                        val kind = classify(f.name) ?: return@forEach
                        // Per-file isolation: one unmovable file must not fail the whole batch.
                        val outcome = runCatching {
                            val finalName = if (kind == Kind.VIDEO) {
                                moveIntoLibrary(f, torrentDir, moviesRoot).also { rootsWritten += display(videoRoot()) }
                            } else {
                                moveIntoLibrary(f, torrentDir, musicRoot).also { rootsWritten += display(musicRoot()) }
                            }
                            if (finalName != f.name) renamed++
                        }
                        if (outcome.isSuccess) moved++ else failedFiles.add(describeFailure(f, outcome))
                    }
                // Establish success BEFORE touching staging: deleting first would destroy
                // fully-downloaded data on a volume-full / permission failure. Files that
                // failed environmentally stay in staging for a retry to salvage.
                require(moved > 0) {
                    "no files finalized${if (failedFiles.isNotEmpty()) "; failed: $failedFiles" else ""}"
                }
                if (failedFiles.isEmpty()) stagingRoot.deleteRecursively()
                buildString {
                    append(
                        "$moved files → " +
                            if (rootsWritten.size == 1) "${rootsWritten.first()}/$albumDir" else rootsWritten.joinToString(),
                    )
                    if (renamed > 0) append(" (renamed: $renamed)")
                    if (failedFiles.isNotEmpty()) append(" (skipped: ${failedFiles.joinToString()})")
                }
            }
        }

    /** Shortens the home prefix to `~` so output notes stay readable. */
    private fun display(path: Path): String =
        path.toAbsolutePath().toString().replace(System.getProperty("user.home"), "~")

    /** Per-file failure detail for the failure/note message: `"track.flac: <cause>"`. */
    private fun describeFailure(file: File, outcome: Result<*>): String {
        val cause = outcome.exceptionOrNull()
        return "${file.name}: ${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error"}"
    }

    /**
     * Preserves the torrent's internal sub-path under the album dir; returns the final
     * file name so the caller can count auto-renames.
     */
    private fun moveIntoLibrary(file: File, torrentDir: File, libraryRoot: Path): String {
        val sub = file.parentFile?.toRelativeString(torrentDir).orEmpty()
        val dir = if (sub.isEmpty()) libraryRoot else libraryRoot.resolve(sub)
        val target = uniqueTarget(dir.resolve(file.name))
        Files.createDirectories(target.parent)
        try {
            Files.move(file.toPath(), target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            // No REPLACE_EXISTING: [uniqueTarget] picked a free name — never overwrite.
            Files.copy(file.toPath(), target)
            Files.delete(file.toPath())
        }
        return target.fileName.toString()
    }

    /**
     * MediaStore-style auto-rename: `name.ext` when free, else `name (1).ext`,
     * `name (2).ext`, … — an existing destination file is never overwritten.
     */
    private fun uniqueTarget(target: Path): Path {
        if (!Files.exists(target)) return target
        val name = target.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name // dotfiles stay whole
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (Files.exists(target.resolveSibling("$base ($i)$ext"))) i++
        return target.resolveSibling("$base ($i)$ext")
    }

    private enum class Kind { AUDIO_IMAGE, VIDEO }

    /** The file's kind when it is media and should be moved; null to skip (cue/log/nfo/…). */
    private fun classify(name: String): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in audioExt || ext in imageExt -> Kind.AUDIO_IMAGE
            ext in videoExt -> Kind.VIDEO
            else -> null
        }
    }
}
