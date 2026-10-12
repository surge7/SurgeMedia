package com.surgemusic.app.downloads

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.surgemusic.core.downloads.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pure path/mime selection logic for the finalizer, extracted into a small object so
 * `composeApp/src/androidUnitTest` can test it without Android framework classes
 * (plan Task 8 Step 2).
 */
internal object MediaFiles {
    val audioExt = setOf("flac", "mp3", "ogg", "oga", "wav", "m4a", "aac", "ape", "wv", "opus")
    val imageExt = setOf("jpg", "jpeg", "png", "webp")

    const val KIND_AUDIO = "audio"
    const val KIND_IMAGE = "image"

    /**
     * (collectionKind, mime) for a staged file, or null for non-media files
     * (logs, nfo, cue, …) — those are skipped by design.
     */
    internal fun classifyFile(name: String): Pair<String?, String>? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in audioExt -> KIND_AUDIO to "audio/$ext"
            in imageExt -> KIND_IMAGE to "image/$ext"
            else -> null
        }
    }

    /**
     * MediaStore [MediaStore.MediaColumns.RELATIVE_PATH] for the given collection kind.
     *
     * Android enforces collection-specific root dirs: audio may live under `Music/…`
     * but images may only live under `Pictures/`, `DCIM/`, `Download/`… — a `Music/…`
     * path on the Images collection throws and (before this was per-file-isolated)
     * failed the whole finalize after the audio had already copied. Cover art therefore
     * lands in `Pictures/SurgeMusic/<album>/` (verified on emulator 2026-10-12).
     */
    internal fun relativePathFor(kind: String, albumDir: String, fileDir: File?, torrentDir: File): String {
        val sub = fileDir?.toRelativeString(torrentDir) ?: ""
        val rootDir = if (kind == KIND_AUDIO) "Music/SurgeMusic" else "Pictures/SurgeMusic"
        val path = "$rootDir/$albumDir/$sub".replace("//", "/")
        return if (path.endsWith("/")) path else "$path/"
    }
}

/**
 * Moves a finished torrent's staged files into the public music library
 * (`Music/SurgeMusic/<sanitized title>/…`) via MediaStore (spec §6): per file
 * insert with `IS_PENDING=1` → stream copy → `IS_PENDING=0`, so MediaScanner
 * picks each file up. Non-media files are skipped; the staging tree is deleted
 * afterwards. Runs off the main thread; any failure surfaces as `Result.failure`.
 */
class MediaStoreFinalizer(private val context: Context) {

    suspend fun finalize(item: DownloadItem, stagingDir: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = File(stagingDir) // …/staging/<topicId>/<torrentName>/files…
                val torrentDir = root.listFiles()?.firstOrNull { it.isDirectory } ?: root
                val albumDir = sanitize(item.request.title).take(120)
                var copied = 0
                val failedFiles = mutableListOf<String>()
                torrentDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .forEach { f ->
                        val (kind, mime) = MediaFiles.classifyFile(f.name) ?: return@forEach
                        // Per-file isolation: one rejected file (e.g. an unsupported
                        // path/mime combination) must not fail the whole batch.
                        val ok = runCatching {
                            val collection = when (kind) {
                                MediaFiles.KIND_AUDIO ->
                                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                MediaFiles.KIND_IMAGE ->
                                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                else -> return@forEach
                            }
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    MediaFiles.relativePathFor(kind, albumDir, f.parentFile, torrentDir),
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                            val uri = context.contentResolver.insert(collection, values)
                                ?: error("insert returned null for ${f.name}")
                            context.contentResolver.openOutputStream(uri)!!.use { out ->
                                f.inputStream().use { it.copyTo(out) }
                            }
                            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                            context.contentResolver.update(uri, done, null, null)
                        }.isSuccess
                        if (ok) copied++ else failedFiles.add(f.name)
                    }
                root.deleteRecursively()
                require(copied > 0) { "no files finalized${if (failedFiles.isNotEmpty()) "; failed: $failedFiles" else ""}" }
                buildString {
                    append("$copied files → Music/SurgeMusic/$albumDir")
                    if (failedFiles.isNotEmpty()) append(" (skipped: ${failedFiles.joinToString()})")
                }
            }
        }

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
