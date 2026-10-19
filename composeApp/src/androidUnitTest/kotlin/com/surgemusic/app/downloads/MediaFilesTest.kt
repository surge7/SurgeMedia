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
            kind = MediaFiles.KIND_AUDIO,
            albumDir = "Artist - Album (2020)",
            fileDir = File("/staging/6918211/TorrentName/CD1"),
            torrentDir = File("/staging/6918211/TorrentName"),
        )
        assertEquals("Music/SurgeMedia/Artist - Album (2020)/CD1/", path)
    }

    @Test
    fun relativePathHandlesDeeplyNestedDirectories() {
        val path = MediaFiles.relativePathFor(
            kind = MediaFiles.KIND_AUDIO,
            albumDir = "Album",
            fileDir = File("/staging/1/T/a/b"),
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMedia/Album/a/b/", path)
    }

    @Test
    fun relativePathForFileAtTorrentRootHasNoSubdirectorySegment() {
        val path = MediaFiles.relativePathFor(
            kind = MediaFiles.KIND_AUDIO,
            albumDir = "Album",
            fileDir = File("/staging/1/T"),
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMedia/Album/", path)
    }

    @Test
    fun relativePathWithNullFileDirStillEndsWithSlash() {
        val path = MediaFiles.relativePathFor(
            kind = MediaFiles.KIND_AUDIO,
            albumDir = "Album",
            fileDir = null,
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMedia/Album/", path)
    }

    @Test
    fun imagesGoToPicturesRootNotMusic() {
        // Android rejects Music/… relative paths on the Images collection (verified on
        // emulator): cover art must land under Pictures/.
        val path = MediaFiles.relativePathFor(
            kind = MediaFiles.KIND_IMAGE,
            albumDir = "Album",
            fileDir = File("/staging/1/T"),
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Pictures/SurgeMedia/Album/", path)
    }

    @Test
    fun emptyAlbumDirCollapsesToLibraryRoot() {
        val path = MediaFiles.relativePathFor(
            kind = MediaFiles.KIND_AUDIO,
            albumDir = "",
            fileDir = null,
            torrentDir = File("/staging/1/T"),
        )
        assertEquals("Music/SurgeMedia/", path)
    }

    @Test
    fun videoExtensionsClassifyAsVideoWithContainerMime() {
        val expected = mapOf(
            "mkv" to "video/x-matroska", "mp4" to "video/mp4", "m4v" to "video/mp4",
            "webm" to "video/webm", "avi" to "video/x-msvideo", "mov" to "video/quicktime",
            "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mpg" to "video/mp2t",
            "mpeg" to "video/mp2t", "vob" to "video/mpeg", "wmv" to "video/x-ms-wmv",
            "flv" to "video/x-flv", "3gp" to "video/3gpp",
        )
        expected.forEach { (ext, mime) ->
            assertEquals(
                "video" to mime,
                MediaFiles.classifyFile("concert.$ext"),
                "extension $ext should classify as video with $mime",
            )
        }
    }

    @Test
    fun videosGoToMoviesRoot() {
        val dir = MediaFiles.relativePathFor(
            MediaFiles.KIND_VIDEO,
            "Pink Floyd - Pompeii",
            null,
            File("/torrent"),
        )
        assertEquals("Movies/SurgeMedia/Pink Floyd - Pompeii/", dir)
    }
}
