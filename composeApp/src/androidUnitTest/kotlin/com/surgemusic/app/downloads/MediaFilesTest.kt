package com.surgemusic.app.downloads

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

    // --- SAF tree → MediaStore mapping (Settings ▸ Storage) ------------------------------

    @Test
    fun treeDocumentIdIsDecodedFromTreeUri() {
        // DocumentsUI tree form; ':' and '/' arrive percent-encoded in the path.
        assertEquals(
            "primary:Music/MyMusic",
            MediaFiles.treeDocumentIdOf("content://com.android.externalstorage.documents/tree/primary%3AMusic%2FMyMusic"),
        )
        // A /document suffix (some pickers return tree+document URIs) must not leak in.
        assertEquals(
            "primary:Movies",
            MediaFiles.treeDocumentIdOf("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies"),
        )
    }

    @Test
    fun nonTreeUrisHaveNoDocumentId() {
        assertNull(MediaFiles.treeDocumentIdOf(""))
        assertNull(MediaFiles.treeDocumentIdOf("/absolute/path"))
        assertNull(MediaFiles.treeDocumentIdOf("content://other.authority/nothing"))
    }

    @Test
    fun audioTreeUnderMusicMapsToMediaStorePrefix() {
        assertEquals(
            "Music/MyMusic/",
            MediaFiles.mediaStorePrefixFor("audio", "primary:Music/MyMusic"),
        )
    }

    @Test
    fun videoTreeUnderMoviesOrDownloadMaps() {
        assertEquals("Movies/Films/", MediaFiles.mediaStorePrefixFor("video", "primary:Movies/Films"))
        assertEquals("Download/vids/", MediaFiles.mediaStorePrefixFor("video", "primary:Download/vids"))
    }

    @Test
    fun audioTreeUnderMoviesHasNoMediaStorePrefix() {
        // Wrong collection for the kind → SAF-only.
        assertNull(MediaFiles.mediaStorePrefixFor("audio", "primary:Movies/ost"))
    }

    @Test
    fun bareStandardRootIsNotMapped() {
        // "Music" itself must not map: per-album deletes would scope to the whole collection.
        assertNull(MediaFiles.mediaStorePrefixFor("audio", "primary:Music"))
        assertNull(MediaFiles.mediaStorePrefixFor("video", "primary:Movies"))
    }

    @Test
    fun removableVolumeAndArbitraryFoldersAreNotMapped() {
        assertNull(MediaFiles.mediaStorePrefixFor("audio", "12AB-3C21:Music/tunes"))
        assertNull(MediaFiles.mediaStorePrefixFor("audio", "primary:MyStuff/tunes"))
    }
}
