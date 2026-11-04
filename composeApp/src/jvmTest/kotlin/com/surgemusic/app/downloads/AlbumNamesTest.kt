package com.surgemusic.app.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared album-name sanitizer: the illegal-chars→`_` + 120-char cap rule the
 * finalizers always applied, plus the degenerate-name guards (blank, `.`, `..`,
 * all-dots → "untitled") that keep a title from resolving to a library root itself.
 */
class AlbumNamesTest {

    @Test
    fun legalTitlesPassThrough() {
        assertEquals("Dark Side Of The Moon", sanitizeAlbumName("Dark Side Of The Moon"))
        assertEquals("AC_DC", sanitizeAlbumName("AC/DC"))
    }

    @Test
    fun illegalCharactersBecomeUnderscores() {
        // a + \/:*?"<>| (9 illegal chars) + i
        assertEquals("a" + "_".repeat(9) + "i", sanitizeAlbumName("a\\/:*?\"<>|i"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("name", sanitizeAlbumName("  name  "))
    }

    @Test
    fun longTitlesAreCappedAt120() {
        assertEquals("x".repeat(120), sanitizeAlbumName("x".repeat(150)))
        assertEquals(120, sanitizeAlbumName("x".repeat(150)).length)
    }

    @Test
    fun blankTitlesBecomeUntitled() {
        assertEquals("untitled", sanitizeAlbumName(""))
        assertEquals("untitled", sanitizeAlbumName("   "))
    }

    @Test
    fun dotOnlyAndLeadingDotTitlesAreGuarded() {
        // "." / ".." / all dots would resolve against a library root as the root itself.
        assertEquals("untitled", sanitizeAlbumName("."))
        assertEquals("untitled", sanitizeAlbumName(".."))
        assertEquals("untitled", sanitizeAlbumName("...."))
        assertEquals("name", sanitizeAlbumName("...name"))
    }

    @Test
    fun cappedAllDotTitlesStillFallBack() {
        // 150 dots → the 120-char cap leaves only dots → still degenerate → fallback.
        assertEquals("untitled", sanitizeAlbumName(".".repeat(150)))
    }

    @Test
    fun trailingDotsAreKept() {
        // Only leading dots are stripped (Windows trailing-dot handling is out of scope).
        assertEquals("name...", sanitizeAlbumName("name..."))
    }
}
