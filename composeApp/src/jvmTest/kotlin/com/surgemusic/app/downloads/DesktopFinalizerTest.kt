package com.surgemusic.app.downloads

import com.surgemusic.app.VideoLibraryParent
import com.surgemusic.app.settings.DesktopSettingsStore
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DesktopFinalizer] against a throwaway `user.home` (roots are resolved per call, so
 * pointing the property at a temp dir redirects everything): success moves media and
 * deletes staging, an existing destination file is auto-renamed `name (1).ext` instead
 * of overwritten, degenerate titles land under "untitled", and a finalize failure keeps
 * the staging tree (and the failed files in it) for retry salvage.
 *
 * Environmental move failures are simulated portably by making a destination unusable —
 * the root's SurgeMedia path pre-created as a *file* makes parent-dir creation throw —
 * rather than POSIX permissions, which behave differently on the Windows CI job.
 */
class DesktopFinalizerTest {

    private val realHome = System.getProperty("user.home")
    private lateinit var home: Path
    private lateinit var settings: DesktopSettingsStore
    private lateinit var finalizer: DesktopFinalizer

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("surge-finalizer-test")
        System.setProperty("user.home", home.toString())
        settings = DesktopSettingsStore(homeDir = home)
        finalizer = DesktopFinalizer(settings)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", realHome)
        home.toFile().deleteRecursively()
    }

    private fun item(title: String) = DownloadItem(
        request = DownloadRequest(id = "t-1", topicId = 1, title = title, forumName = "f", sizeBytes = 10L),
    )

    /** `staging/<topicId>/<torrentName>/<files…>` — the layout DownloadManager hands over. */
    private fun stage(vararg files: String): Path {
        val staging = Files.createTempDirectory("surge-staging")
        files.forEach { rel ->
            val f = staging.resolve("1").resolve("Torrent").resolve(rel)
            f.parent.toFile().mkdirs()
            f.toFile().writeBytes(ByteArray(16))
        }
        return staging
    }

    /** Makes a library root unusable: SurgeMedia exists as a *file*, so dir creation throws. */
    private fun breakRoot(parent: String) {
        home.resolve(parent).toFile().mkdirs()
        home.resolve(parent).resolve("SurgeMedia").toFile().writeBytes(ByteArray(0))
    }

    @Test
    fun successMovesMediaIntoTheLibraryAndDeletesStaging() = runTest {
        val staging = stage("01.flac", "cover.jpg", "film.mkv", "notes.nfo") // nfo skipped

        val note = finalizer.finalize(item("My Album"), staging.toString()).getOrThrow()

        assertTrue(home.resolve("Music/SurgeMedia/My Album/Torrent/01.flac").toFile().exists())
        assertTrue(home.resolve("Music/SurgeMedia/My Album/Torrent/cover.jpg").toFile().exists())
        assertTrue(home.resolve("$VideoLibraryParent/SurgeMedia/My Album/Torrent/film.mkv").toFile().exists())
        assertFalse(staging.toFile().exists()) // fully successful batch → staging tree gone
        assertTrue(note.startsWith("3 files →"))
        assertFalse(note.contains("skipped") || note.contains("renamed"))
    }

    @Test
    fun existingDestinationFilesAutoRenameInsteadOfOverwriting() = runTest {
        val first = stage("01.flac", "cover.jpg")
        finalizer.finalize(item("My Album"), first.toString()).getOrThrow()

        // A re-download finalizes the same names again — the originals must survive.
        val second = stage("01.flac", "cover.jpg")
        second.resolve("1").resolve("Torrent").resolve("01.flac").toFile().writeBytes(ByteArray(32))

        val note = finalizer.finalize(item("My Album"), second.toString()).getOrThrow()

        val album = home.resolve("Music/SurgeMedia/My Album/Torrent")
        assertEquals(16, album.resolve("01.flac").toFile().length()) // first run untouched
        assertEquals(32, album.resolve("01 (1).flac").toFile().length()) // second run aside
        assertTrue(album.resolve("cover.jpg").toFile().exists())
        assertTrue(album.resolve("cover (1).jpg").toFile().exists())
        assertTrue(note.contains("renamed: 2"))
        assertFalse(second.toFile().exists()) // success → staging deleted
    }

    @Test
    fun degenerateTitlesLandUnderUntitledNotAtTheRoot() = runTest {
        listOf("", "   ", ".", "..", "...").forEach { title ->
            val staging = stage("01.flac")

            finalizer.finalize(item(title), staging.toString()).getOrThrow()

            assertTrue(
                home.resolve("Music/SurgeMedia/untitled/Torrent/01.flac").toFile().exists(),
                "title ${title.length} chars long should finalize under 'untitled'",
            )
        }
        // The library root itself holds only the untitled dir — never got deleted.
        assertEquals(
            listOf("untitled"),
            home.resolve("Music/SurgeMedia").toFile().listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun environmentalFailureKeepsFailedFilesInStaging() = runTest {
        val staging = stage("01.flac", "film.mkv")
        breakRoot(VideoLibraryParent) // only the video destination — music still finalizes

        val note = finalizer.finalize(item("My Album"), staging.toString()).getOrThrow()

        assertTrue(home.resolve("Music/SurgeMedia/My Album/Torrent/01.flac").toFile().exists())
        assertTrue(staging.resolve("1").resolve("Torrent").resolve("film.mkv").toFile().exists())
        assertTrue(staging.toFile().exists()) // failed batch keeps staging for retry salvage
        assertTrue(note.contains("skipped: film.mkv"))
    }

    @Test
    fun allMediaFailingFailsTheFinalizeWithDetailsAndKeepsStaging() = runTest {
        val staging = stage("01.flac", "02.flac")
        breakRoot("Music") // destination unusable for every file in the batch

        val result = finalizer.finalize(item("My Album"), staging.toString())

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("no files finalized"))
        assertTrue(message.contains("01.flac:") && message.contains("02.flac:")) // per-file causes
        assertTrue(staging.toFile().exists())
    }

    @Test
    fun allFilesSkippedFailsTheFinalizeAndKeepsStaging() = runTest {
        val staging = stage("album.cue", "notes.nfo") // non-media: skipped by design

        val result = finalizer.finalize(item("My Album"), staging.toString())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no files finalized"))
        assertTrue(staging.toFile().exists())
    }

    private fun runTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }
}
