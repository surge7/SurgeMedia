package com.surgemusic.app.downloads

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure path/mime selection logic of the MediaStore finalizer (plan Task 8 Step 2) —
 * no Android framework classes involved, so it runs in plain JVM unit tests.
 */
class MediaFilesTest {

    @Test
    fun audioExtensionsClassifyAsAudioWithMime() {
        val audioExts = setOf("flac", "mp3", "ogg", "oga", "wav", "m4a", "aac", "ape", "wv", "opus")
        audioExts.forEach { ext ->
            assertEquals(
                "audio" to "audio/$ext",
                MediaFiles.classifyFile("01 - Track.$ext"),
                "extension $ext should classify as audio",
            )
        }
    }

    @Test
    fun imageExtensionsClassifyAsImageWithMime() {
        val imageExts = setOf("jpg", "jpeg", "png", "webp")
        imageExts.forEach { ext ->
            assertEquals(
                "image" to "image/$ext",
                MediaFiles.classifyFile("cover.$ext"),
                "extension $ext should classify as image",
            )
        }
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals("audio" to "audio/flac", MediaFiles.classifyFile("TRACK.FLAC"))
        assertEquals("image" to "image/jpg", MediaFiles.classifyFile("Cover.JPG"))
    }

    @Test
    fun nonMediaFilesAreSkippedByDesign() {
        // logs, nfo metadata, cue sheets, checksums, torrent itself, plain text
        listOf("album.nfo", "download.log", "artwork.txt", "image.cue", "backup.torrent", "MD5")
            .forEach { name ->
                assertNull(MediaFiles.classifyFile(name), "$name should be skipped (null)")
            }
    }

    @Test
    fun nameWithoutExtensionIsSkipped() {
        assertNull(MediaFiles.classifyFile("README"))
    }

    @Test
    fun relativePathIncludesAlbumAndSubdirectoryWithTrailingSlash() {
        val path = MediaFiles.relativePathFor(
            albumDir = "Artist - Album (2020)",
            fileDir = File("/staging/6918211/TorrentName/CD1"),
            torrentDir = File("/staging/6918211/TorrentName"),
        )
        assertEquals("Music/SurgeMusic/Artist - Album (2020)/CD1/", path)
    }

    @Test
    fun relativePathHandlesDeeplyNestedDirectories() {
        val path = MediaFiles.relativePathFor(
            albumDir = "Album",
            fileDir = File("/staging/1/T/a/b"),
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMusic/Album/a/b/", path)
    }

    @Test
    fun relativePathForFileAtTorrentRootHasNoSubdirectorySegment() {
        val path = MediaFiles.relativePathFor(
            albumDir = "Album",
            fileDir = File("/staging/1/T"),
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMusic/Album/", path)
    }

    @Test
    fun relativePathWithNullFileDirStillEndsWithSlash() {
        val path = MediaFiles.relativePathFor(
            albumDir = "Album",
            fileDir = null,
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMusic/Album/", path)
    }

    @Test
    fun emptyAlbumDirCollapsesToLibraryRoot() {
        val path = MediaFiles.relativePathFor(
            albumDir = "",
            fileDir = null,
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMusic/", path)
    }
}
