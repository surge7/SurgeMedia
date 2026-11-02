package com.surgemusic.app.downloads

import com.surgemusic.app.VideoLibraryParent
import com.surgemusic.app.settings.DesktopSettingsStore
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
 * delete (subtree, and loose files for UNTITLED), delete-all, and the Settings ▸ Storage
 * custom folders (listed under their own root; never swept by delete-all).
 *
 * The built-in video parent is platform-dependent (`Movies` on macOS, `Videos` on
 * Windows/Linux — [VideoLibraryParent]), and label separators follow the host filesystem,
 * so every expected path goes through [label] / [videoParent] instead of literals — the
 * suite runs unchanged on macOS dev machines and the Windows CI job.
 */
class DesktopLibraryTest {

    private val realHome = System.getProperty("user.home")
    private lateinit var home: java.nio.file.Path
    private lateinit var settings: DesktopSettingsStore
    private lateinit var library: DesktopLibrary

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("surge-library-test")
        System.setProperty("user.home", home.toString())
        settings = DesktopSettingsStore(homeDir = home)
        library = DesktopLibrary(settings)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", realHome)
        home.toFile().deleteRecursively()
    }

    /** The root path as [DesktopLibrary] labels it — home shortened to `~`, host separators. */
    private fun label(root: String): String =
        home.resolve(root).toAbsolutePath().toString().replace(home.toString(), "~")

    private fun file(root: String, relPath: String, bytes: Int) {
        val f = home.resolve(root).resolve(relPath).toFile()
        f.parentFile.mkdirs()
        f.writeBytes(ByteArray(bytes))
    }

    @Test
    fun listAggregatesReleasesAcrossRootsWithDetails() = runTest {
        file("Music/SurgeMedia", "Album A/CD1/01.flac", 100)
        file("Music/SurgeMedia", "Album A/CD1/02.flac", 50)
        file("$VideoLibraryParent/SurgeMedia", "Film B/film.mkv", 1000)
        file("Music/SurgeMusic", "Legacy Album/x.mp3", 10) // pre-rebrand root
        file("Music/Unrelated", "other.flac", 999) // outside any root — ignored

        val releases = library.list().getOrThrow()

        assertEquals(
            listOf(
                DownloadedRelease("music", "Album A", label("Music/SurgeMedia"), "Music", 2, 150L),
                DownloadedRelease("movies", "Film B", label("$VideoLibraryParent/SurgeMedia"), "Movies", 1, 1000L),
                DownloadedRelease("music_legacy", "Legacy Album", label("Music/SurgeMusic") + " (legacy)", "Music", 1, 10L),
            ),
            releases, // sorted by title: Album A, Film B, Legacy Album
        )
    }

    @Test
    fun deleteRemovesTheReleaseSubtreeOnly() = runTest {
        file("$VideoLibraryParent/SurgeMedia", "Film B/film.mkv", 1000)
        file("$VideoLibraryParent/SurgeMedia", "Film C/c.mkv", 500)

        val deleted = library
            .delete(DownloadedRelease("movies", "Film B", label("$VideoLibraryParent/SurgeMedia"), "Movies", 1, 1000L))
            .getOrThrow()

        assertEquals(1, deleted)
        assertFalse(home.resolve("$VideoLibraryParent/SurgeMedia/Film B").toFile().exists())
        assertTrue(home.resolve("$VideoLibraryParent/SurgeMedia/Film C/c.mkv").toFile().exists())
    }

    @Test
    fun deleteUntangledOnlyLooseFilesAtTheRoot() = runTest {
        file("Music/SurgeMedia", "loose.flac", 10)
        file("Music/SurgeMedia", "Album/01.flac", 20)

        val deleted = library
            .delete(DownloadedRelease("music", DownloadedRelease.UNTITLED, label("Music/SurgeMedia"), "Music", 1, 10L))
            .getOrThrow()

        assertEquals(1, deleted)
        assertFalse(home.resolve("Music/SurgeMedia/loose.flac").toFile().exists())
        assertTrue(home.resolve("Music/SurgeMedia/Album/01.flac").toFile().exists())
    }

    @Test
    fun deleteAllSweepsEveryRootIncludingLegacy() = runTest {
        file("Music/SurgeMedia", "Album/01.flac", 10)
        file("$VideoLibraryParent/SurgeMedia", "Film/f.mkv", 20)
        file("Music/SurgeMusic", "Legacy/x.mp3", 30)

        val deleted = library.deleteAll().getOrThrow()

        assertEquals(3, deleted)
        assertTrue(library.list().getOrThrow().isEmpty())
        // Roots themselves stay (as empty dirs) — only their children are swept.
        assertTrue(home.resolve("Music/SurgeMedia").toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun customFolderIsListedAndDeletableButNeverSwept() = runTest {
        val custom = home.resolve("Tunes").toString()
        settings.setMusicFolder(custom)
        file("Tunes", "New Album/01.flac", 100)
        file("Music/SurgeMedia", "Old Album/01.flac", 10)

        val releases = library.list().getOrThrow()
        assertEquals(
            listOf("New Album", "Old Album"),
            releases.map { it.title },
        )
        // The custom root shows its path (home shortened to ~); the built-in stays for old files.
        assertEquals(label("Tunes"), releases.first { it.title == "New Album" }.locationLabel)

        // delete-all sweeps only the app's built-in roots — the custom folder is untouched.
        assertEquals(1, library.deleteAll().getOrThrow())
        assertTrue(home.resolve("Tunes/New Album/01.flac").toFile().exists())
        assertFalse(home.resolve("Music/SurgeMedia/Old Album/01.flac").toFile().exists())

        // Per-release delete still works inside the custom folder.
        assertEquals(1, library.delete(releases.first { it.title == "New Album" }).getOrThrow())
        assertFalse(home.resolve("Tunes/New Album/01.flac").toFile().exists())
    }

    @Test
    fun listOnMissingRootsIsEmptyNotFailure() = runTest {
        assertTrue(library.list().getOrThrow().isEmpty())
    }

    private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
