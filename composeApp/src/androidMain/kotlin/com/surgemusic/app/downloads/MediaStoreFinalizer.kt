package com.surgemusic.app.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.surgemusic.app.settings.SettingsStore
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
    val videoExt = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mpg", "mpeg", "vob", "wmv", "flv", "3gp",
    )

    const val KIND_AUDIO = "audio"
    const val KIND_IMAGE = "image"
    const val KIND_VIDEO = "video"

    /** Correct container mimes for MediaStore.Video (a bare `video/<ext>` would be invalid). */
    internal val videoMimes = mapOf(
        "mkv" to "video/x-matroska",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mpg" to "video/mp2t",
        "mpeg" to "video/mp2t",
        "vob" to "video/mpeg",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "3gp" to "video/3gpp",
    )

    /** Standard MediaStore collection roots each kind may live under (API 29+ scoped storage). */
    internal val audioCollectionRoots = setOf("Music", "Download", "Recordings")
    internal val videoCollectionRoots = setOf("Movies", "DCIM", "Download")
    internal val imageCollectionRoots = setOf("Pictures", "DCIM", "Download")

    /**
     * Tree documentId ("primary:Music/MyMusic") encoded in a persisted SAF tree URI
     * string, or null when the string is not a tree URI. Pure string work so plain JVM
     * unit tests can drive it.
     */
    internal fun treeDocumentIdOf(treeUriString: String): String? {
        if (!treeUriString.startsWith("content://")) return null
        val encoded = treeUriString
            .removePrefix("content://")
            .substringAfter("tree/", "")
            .substringBefore('/')
        if (encoded.isEmpty()) return null
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
    }

    /**
     * MediaStore RELATIVE_PATH prefix ("Music/MyMusic/") that [documentId] maps to for
     * [kind], or null when the tree has no MediaStore equivalent — not the primary
     * volume, or outside the kind's allowed standard roots (then the caller writes via
     * SAF). The bare standard root itself ("Music", "Movies", …) is deliberately NOT
     * mappable: per-album deletes under e.g. "Music/" would scope to the user's whole
     * collection.
     */
    internal fun mediaStorePrefixFor(kind: String, documentId: String): String? {
        if (!documentId.contains(':')) return null
        if (documentId.substringBefore(':') != "primary") return null
        val path = documentId.substringAfter(':').trim('/')
        if (!path.contains('/')) return null // volume root or bare standard root → SAF only
        val allowed = when (kind) {
            KIND_AUDIO -> audioCollectionRoots
            KIND_VIDEO -> videoCollectionRoots
            KIND_IMAGE -> imageCollectionRoots
            else -> return null
        }
        if (path.substringBefore('/') !in allowed) return null
        return "$path/"
    }

    /**
     * (collectionKind, mime) for a staged file, or null for non-media files
     * (logs, nfo, cue, …) — those are skipped by design.
     */
    internal fun classifyFile(name: String): Pair<String?, String>? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in audioExt -> KIND_AUDIO to "audio/$ext"
            in imageExt -> KIND_IMAGE to "image/$ext"
            in videoExt -> KIND_VIDEO to (videoMimes[ext] ?: "video/mp4")
            else -> null
        }
    }

    /**
     * Album-dir name from a torrent title: filesystem/MediaStore-illegal characters
     * become `_`. Shared by the finalizer (where files land) and `MediaStoreCleaner`
     * (which must match the exact same dir to delete them).
     */
    internal fun sanitizeDirName(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    /**
     * MediaStore [MediaStore.MediaColumns.RELATIVE_PATH] for the given collection kind.
     *
     * Android enforces collection-specific root dirs: audio may live under `Music/…`
     * but images may only live under `Pictures/`, `DCIM/`, `Download/`… — a `Music/…`
     * path on the Images collection throws and (before this was per-file-isolated)
     * failed the whole finalize after the audio had already copied. Cover art therefore
     * lands in `Pictures/SurgeMedia/<album>/` (verified on emulator 2026-10-12), video
     * in `Movies/SurgeMedia/<album>/` (same collection-root rule for Video).
     */
    internal fun collectionRootFor(kind: String): String = when (kind) {
        KIND_AUDIO -> "Music/SurgeMedia"
        KIND_IMAGE -> "Pictures/SurgeMedia"
        else -> "Movies/SurgeMedia"
    }
}

/**
 * Moves a finished torrent's staged files into the user's media libraries (spec §6)
 * with per-file isolation: media files only, one rejected file cannot fail the batch,
 * staging tree deleted afterwards, all-skipped batch fails with the skipped list.
 *
 * Destination per kind (Settings ▸ Storage): a custom folder wins when set — if its SAF
 * tree maps onto a MediaStore standard root ("primary:Music/MyMusic" → RELATIVE_PATH
 * `Music/MyMusic/…`), files go through MediaStore and are indexed like the defaults
 * (visible to every player); any other tree (removable storage, arbitrary folders) is
 * written through SAF [DocumentFile] — the files land in the chosen folder, though
 * Android does not index arbitrary locations into MediaStore. Cover art always stays on
 * the default `Pictures/SurgeMedia/` collection (MediaStore restricts images to
 * Pictures/DCIM/Download roots). Defaults without a custom folder are unchanged:
 * `Music/SurgeMedia/…`, `Movies/SurgeMedia/…` via MediaStore inserts with
 * `IS_PENDING=1` → stream copy → `IS_PENDING=0`, so MediaScanner picks each file up.
 * Runs off the main thread; any failure surfaces as `Result.failure`.
 */
class MediaStoreFinalizer(
    private val context: Context,
    private val settings: SettingsStore,
) {

    /** Where files of one kind land. */
    private sealed interface Dest {
        /** MediaStore insert with this fixed RELATIVE_PATH prefix ("Music/SurgeMedia/"). */
        data class Store(val prefix: String) : Dest

        /** Raw SAF copy preserving the torrent's sub-path under the tree. */
        data class Tree(val treeUri: Uri) : Dest
    }

    private fun destFor(kind: String): Dest {
        val custom = when (kind) {
            MediaFiles.KIND_AUDIO -> settings.musicFolder.value
            MediaFiles.KIND_VIDEO -> settings.videoFolder.value
            else -> "" // cover art keeps its default Pictures collection
        }
        if (custom.isBlank()) return Dest.Store(defaultPrefixFor(kind))
        val uri = runCatching { Uri.parse(custom) }.getOrNull()
            ?.takeIf { it.scheme == "content" } ?: return Dest.Store(defaultPrefixFor(kind))
        val docId = MediaFiles.treeDocumentIdOf(custom)
        val prefix = docId?.let { MediaFiles.mediaStorePrefixFor(kind, it) }
        return if (prefix != null) Dest.Store(prefix) else Dest.Tree(uri)
    }

    private fun defaultPrefixFor(kind: String): String = when (kind) {
        MediaFiles.KIND_AUDIO -> "Music/SurgeMedia/"
        MediaFiles.KIND_VIDEO -> "Movies/SurgeMedia/"
        else -> "Pictures/SurgeMedia/"
    }

    suspend fun finalize(item: DownloadItem, stagingDir: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = File(stagingDir) // …/staging/<topicId>/<torrentName>/files…
                val torrentDir = root.listFiles()?.firstOrNull { it.isDirectory } ?: root
                val albumDir = sanitize(item.request.title).take(120)
                val destByKind = HashMap<String, Dest>()
                var copied = 0
                val failedFiles = mutableListOf<String>()
                val rootsWritten = linkedSetOf<String>() // destinations files actually landed in
                torrentDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .forEach { f ->
                        val classified = MediaFiles.classifyFile(f.name) ?: return@forEach
                        val kind = classified.first ?: return@forEach
                        val mime = classified.second ?: return@forEach
                        val dest = destByKind.getOrPut(kind) { destFor(kind) }
                        val sub = f.parentFile?.toRelativeString(torrentDir).orEmpty()
                        // Per-file isolation: one rejected file (e.g. an unsupported
                        // path/mime combination) must not fail the whole batch.
                        val ok = runCatching {
                            when (dest) {
                                is Dest.Store -> copyViaMediaStore(f, kind, mime, dest.prefix, albumDir, sub)
                                is Dest.Tree -> copyIntoTree(f, mime, dest.treeUri, albumDir, sub)
                            }?.also { rootsWritten += it }
                                ?: error("finalize returned null for ${f.name}")
                        }.isSuccess
                        if (ok) copied++ else failedFiles.add(f.name)
                    }
                root.deleteRecursively()
                require(copied > 0) { "no files finalized${if (failedFiles.isNotEmpty()) "; failed: $failedFiles" else ""}" }
                buildString {
                    append(
                        "$copied files → " +
                            if (rootsWritten.size == 1) rootsWritten.first() else rootsWritten.joinToString(),
                    )
                    if (failedFiles.isNotEmpty()) append(" (skipped: ${failedFiles.joinToString()})")
                }
            }
        }

    /** MediaStore insert (IS_PENDING → copy → publish); returns the destination label. */
    private fun copyViaMediaStore(
        file: File,
        kind: String,
        mime: String,
        prefix: String,
        albumDir: String,
        sub: String,
    ): String {
        val collection = when (kind) {
            MediaFiles.KIND_AUDIO ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaFiles.KIND_IMAGE ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaFiles.KIND_VIDEO ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> error("unreachable kind $kind")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, prefix + albumDir + "/" + sub)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("insert returned null for ${file.name}")
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
        return prefix.trimEnd('/')
    }

    /**
     * SAF copy: walks/creates `<albumDir>/<sub>` under the tree, then streams the file
     * into a new document. Returns the tree documentId path as the label
     * ("Music/MyMusic"), or null on failure.
     */
    private fun copyIntoTree(file: File, mime: String, treeUri: Uri, albumDir: String, sub: String): String? {
        var dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val rel = (albumDir + "/" + sub).split('/').filter { it.isNotBlank() }
        rel.forEach { seg ->
            val existing = dir.findFile(seg)
            dir = when {
                existing?.isDirectory == true -> existing
                else -> dir.createDirectory(seg) ?: return null
            }
        }
        val target = dir.createFile(mime, file.name) ?: return null
        context.contentResolver.openOutputStream(target.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return null
        return MediaFiles.treeDocumentIdOf(treeUri.toString())
    }

    private fun sanitize(s: String) = MediaFiles.sanitizeDirName(s)
}
