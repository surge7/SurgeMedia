package com.surgemusic.app.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure grouping/filter logic of the Downloaded tab's MediaStore library — no Android
 * framework classes involved, so it runs in plain JVM unit tests (same pattern as
 * [MediaFilesTest]).
 */
class LibraryGroupingTest {

    @Test
    fun albumOfExtractsFirstPathSegmentAfterRoot() {
        assertEquals("Artist - Album", LibraryGrouping.albumOf("Music/SurgeMedia/Artist - Album/CD1/01.flac", "Music/SurgeMedia/"))
        assertEquals("Movie (2024)", LibraryGrouping.albumOf("Movies/SurgeMedia/Movie (2024)/movie.mkv", "Movies/SurgeMedia/"))
    }

    @Test
    fun albumOfBucketsFilesAtRootToUntitled() {
        // MediaStore RELATIVE_PATH is directory-only: a file directly at the root has
        // RELATIVE_PATH == the root prefix itself.
        assertEquals(DownloadedRelease.UNTITLED, LibraryGrouping.albumOf("Music/SurgeMedia/", "Music/SurgeMedia/"))
        assertEquals(DownloadedRelease.UNTITLED, LibraryGrouping.albumOf("Movies/SurgeMedia/", "Movies/SurgeMedia/"))
    }

    @Test
    fun albumOfRejectsPathsOutsideTheRoot() {
        assertNull(LibraryGrouping.albumOf("Music/OtherLib/Album/x.flac", "Music/SurgeMedia/"))
        assertNull(LibraryGrouping.albumOf("Download/whatever.zip", "Music/SurgeMedia/"))
        assertNull(LibraryGrouping.albumOf("", "Music/SurgeMedia/"))
    }

    @Test
    fun buildReleasesAggregatesFilesAndBytesPerAlbum() {
        val releases = LibraryGrouping.buildReleases(
            rootId = "movies",
            kindLabel = "Movies",
            locationLabel = "Movies",
            rootPrefix = "Movies/SurgeMedia/",
            rows = listOf(
                "Movies/SurgeMedia/Film A/film.mkv" to 1_000L,
                "Movies/SurgeMedia/Film A/extras/sub.srt" to 100L,
                "Movies/SurgeMedia/Film B/b.mkv" to 5_000L,
                "Movies/Other/x" to 999L, // outside the root — dropped
            ),
        )
        assertEquals(
            listOf(
                DownloadedRelease("movies", "Film A", "Movies", "Movies", 2, 1_100L),
                DownloadedRelease("movies", "Film B", "Movies", "Movies", 1, 5_000L),
            ),
            releases,
        )
    }

    @Test
    fun matchesReleaseAcceptsOnlyTheAlbumSubtree() {
        val prefix = "Music/SurgeMedia/"
        assertTrue(LibraryGrouping.matchesRelease("Music/SurgeMedia/Album/CD1/01.flac", prefix, "Album"))
        assertFalse(LibraryGrouping.matchesRelease("Music/SurgeMedia/Album 2/01.flac", prefix, "Album"))
        assertFalse(LibraryGrouping.matchesRelease("Music/SurgeMedia/", prefix, "Album"))
    }

    @Test
    fun matchesReleaseUntangledFromSiblingWithSharedTitlePrefix() {
        val prefix = "Movies/SurgeMedia/"
        assertFalse(LibraryGrouping.matchesRelease("Movies/SurgeMedia/Sequel/sequel.mkv", prefix, "Film"))
        assertFalse(LibraryGrouping.matchesRelease("Movies/SurgeMedia/Film 2/f2.mkv", prefix, "Film"))
        assertTrue(LibraryGrouping.matchesRelease("Movies/SurgeMedia/Film (2020)/f.mkv", prefix, "Film (2020)"))
    }

    @Test
    fun matchesReleaseUntitledMatchesOnlyLooseFiles() {
        val prefix = "Music/SurgeMedia/"
        assertTrue(LibraryGrouping.matchesRelease("Music/SurgeMedia/loose.flac", prefix, DownloadedRelease.UNTITLED))
        assertFalse(LibraryGrouping.matchesRelease("Music/SurgeMedia/Album/01.flac", prefix, DownloadedRelease.UNTITLED))
    }
}
