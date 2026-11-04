package com.surgemusic.app.downloads

/** Filesystem/MediaStore-illegal album-name characters — everything else is kept. */
private val ILLEGAL_ALBUM_CHARS = Regex("[\\\\/:*?\"<>|]")

/** Same cap the finalizers used to apply per call site with `.take(120)`. */
internal const val MAX_ALBUM_NAME_LENGTH = 120

/** Fallback for blank / dot-only titles. */
internal const val UNTITLED_ALBUM_NAME = "untitled"

/**
 * Shared album-directory-name sanitizer — the single source of truth for where a
 * download's files land ([DesktopFinalizer], [MediaStoreFinalizer] via
 * `MediaFiles.sanitizeDirName`) and where `DesktopFileCleaner` / `MediaStoreCleaner`
 * must look again to delete them. Was duplicated per platform (illegal chars → `_`,
 * then a `.take(120)` cap at every call site); the shared version adds guards the
 * local copies lacked: blank, `.`/`..` and all-dot names — which resolve against a
 * library root as the root itself — become [UNTITLED_ALBUM_NAME], and the result is
 * never empty.
 */
fun sanitizeAlbumName(raw: String): String {
    val cleaned = raw.replace(ILLEGAL_ALBUM_CHARS, "_").trim().trimStart('.')
    // trimStart collapses "." / ".." / all-dot names to blank before this check, so
    // no degenerate segment can ever reach a root resolve.
    if (cleaned.isBlank()) return UNTITLED_ALBUM_NAME
    return cleaned.take(MAX_ALBUM_NAME_LENGTH).trim().trimStart('.').ifBlank { UNTITLED_ALBUM_NAME }
}
