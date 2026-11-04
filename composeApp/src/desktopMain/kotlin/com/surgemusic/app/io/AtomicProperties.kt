package com.surgemusic.app.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * Shared disk discipline for the plaintext Properties stores (session cookie, settings):
 * the snapshot is written to a sibling temp file and moved onto [target] with
 * `ATOMIC_MOVE` — a reader (or a crash mid-write) can never observe a torn/partial file,
 * and the old file is either fully replaced or untouched. The temp file is created with
 * POSIX `rw-------` perms and the rename keeps the inode's permissions, so the target is
 * 0600 from birth — that matters for session.properties, which carries the rutracker
 * bb_session cookie in plaintext.
 *
 * The POSIX chmod is best-effort: on filesystems without the attribute view (Windows)
 * it is skipped silently — the OS default ACL still scopes the file to the user account.
 */
internal fun Properties.storeAtomicPrivate(target: Path) {
    Files.createDirectories(target.parent)
    val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
    try {
        runCatching {
            Files.getFileAttributeView(tmp, PosixFileAttributeView::class.java)
                ?.setPermissions(PosixFilePermissions.fromString("rw-------"))
        } // no POSIX perms (Windows/NTFS) → skip silently
        Files.newOutputStream(tmp).use { store(it, null) }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
