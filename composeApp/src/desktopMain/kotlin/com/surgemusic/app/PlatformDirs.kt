package com.surgemusic.app

/**
 * Desktop platform facts used by the storage layer (and window chrome). Kept in one spot
 * so macOS behaviour stays byte-identical while Windows builds get their own platform
 * conventions from the same shared code.
 */

/** True on Windows; the DWM window-chrome calls in [WindowsWindowChrome] are Windows-only. */
internal val IsWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

/**
 * Parent folder of the built-in video library: macOS exposes `~/Movies` (Finder's media
 * folder), Windows and Linux shells expose `~/Videos`. The music parent (`Music`) is
 * called the same thing everywhere, so it needs no per-OS mapping.
 */
internal val VideoLibraryParent: String = if (IsMacOs) "Movies" else "Videos"
