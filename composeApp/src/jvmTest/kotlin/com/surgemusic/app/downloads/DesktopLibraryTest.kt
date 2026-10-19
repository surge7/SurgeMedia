package com.surgemusic.app.downloads

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DesktopLibrary] against a throwaway `user.home` (the roots are resolved per call, so
 * pointing the property at a temp dir redirects everything). Covers the Downloaded tab's
 * full control surface: list with per-release aggregation across roots, per-release
 * delete (subtree, and loose files for UNTITLED), and delete-all.
 */
class DesktopLibraryTest {

    private val realHome = System.getProperty("user.home")
    private lateinit var home: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("surge-library-test")
        System.setProperty("user.home", home.toString())
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", realHome)
        home.toFile().deleteRecursively()
    }

    private fun file(root: String, relPath: String, bytes: Int) {
        val f = home.resolve(root).resolve(relPath).toFile()
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun listAggregatesReleasesAcrossRootsWithDetails() = runTest {
        file("Music/SurgeMedia", "Album A/CD1/01.flac", 100)
        file("Music/SurgeMedia", "Album A/CD1/02.flac", 50)
        file("Movies/SurgeMedia", "Film B/film.mkv", 1000)
        file("Music/SurgeMusic", "Legacy Album/x.mp3", 10) // pre-rebrand root
        file("Music/Unrelated", "other.flac", 999) // outside any root — ignored

        val releases = DesktopLibrary.list().getOrThrow()

        assertEquals(
            listOf(
                DownloadedRelease("music", "Album A", "~/Music/SurgeMedia", "Music", 2, 150L),
                DownloadedRelease("movies", "Film B", "~/Movies/SurgeMedia", "Movies", 1, 1000L),
                DownloadedRelease("music_legacy", "Legacy Album", "~/Music/SurgeMusic (legacy)", "Music", 1, 10L),
            ),
            releases, // sorted by title: Album A, Film B, Legacy Album
        )
    }

    @Test
    fun deleteRemovesTheReleaseSubtreeOnly() = runTest {
        file("Movies/SurgeMedia", "Film B/film.mkv", 1000)
        file("Movies/SurgeMedia", "Film C/c.mkv", 500)

        val deleted = DesktopLibrary
            .delete(DownloadedRelease("movies", "Film B", "~/Movies/SurgeMedia", "Movies", 1, 1000L))
            .getOrThrow()

        assertEquals(1, deleted)
        assertFalse(home.resolve("Movies/SurgeMedia/Film B").toFile().exists())
        assertTrue(home.resolve("Movies/SurgeMedia/Film C/c.mkv").toFile().exists())
    }

    @Test
    fun deleteUntangledOnlyLooseFilesAtTheRoot() = runTest {
        file("Music/SurgeMedia", "loose.flac", 10)
        file("Music/SurgeMedia", "Album/01.flac", 20)

        val deleted = DesktopLibrary
            .delete(DownloadedRelease("music", DownloadedRelease.UNTITLED, "~/Music/SurgeMedia", "Music", 1, 10L))
            .getOrThrow()

        assertEquals(1, deleted)
        assertFalse(home.resolve("Music/SurgeMedia/loose.flac").toFile().exists())
        assertTrue(home.resolve("Music/SurgeMedia/Album/01.flac").toFile().exists())
    }

    @Test
    fun deleteAllSweepsEveryRootIncludingLegacy() = runTest {
        file("Music/SurgeMedia", "Album/01.flac", 10)
        file("Movies/SurgeMedia", "Film/f.mkv", 20)
        file("Music/SurgeMusic", "Legacy/x.mp3", 30)

        val deleted = DesktopLibrary.deleteAll().getOrThrow()

        assertEquals(3, deleted)
        assertTrue(DesktopLibrary.list().getOrThrow().isEmpty())
        // Roots themselves stay (as empty dirs) — only their children are swept.
        assertTrue(home.resolve("Music/SurgeMedia").toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun listOnMissingRootsIsEmptyNotFailure() = runTest {
        assertTrue(DesktopLibrary.list().getOrThrow().isEmpty())
    }

    private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
