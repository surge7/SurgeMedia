package com.surgemusic.app.downloads

import com.surgemusic.core.downloads.PersistedDownload
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * [DesktopQueuePersistence] load-side prune against a throwaway home dir: terminal
 * entries (DONE/FAILED/CANCELLED) must survive the staging-path prune and the orphan
 * sweep (their staging is legitimately deleted after finalize), non-terminal entries
 * with vanished staging still drop, and pre-history files (no state/bytesTotal/note
 * fields) load with their defaults.
 */
class DesktopQueuePersistenceTest {

    private fun persisted(id: String, stagingPath: String, state: String = "QUEUED") =
        PersistedDownload(
            id = id,
            topicId = 6918211L,
            title = "Artist - Album",
            forumName = "Rock",
            torrentBytesBase64 = null,
            stagingPath = stagingPath,
            wantedFiles = emptyList(),
            state = state,
        )

    /** Writes the store file via the real store — the same JSON a save produces. */
    private suspend fun writeQueue(home: Path, entries: List<PersistedDownload>) {
        DesktopQueuePersistence(home).save(entries)
    }

    @Test
    fun terminalEntriesSurviveStagingPruneAndOrphanSweep() = runTest {
        val home = createTempDirectory("surge-queue-test")
        try {
            val stagingRoot = home.resolve(".surgemusic").resolve("staging")
            val liveDir = stagingRoot.resolve("t-live")
            val historyDir = stagingRoot.resolve("t-cancelled")
            Files.createDirectories(liveDir)
            Files.createDirectories(historyDir)
            writeQueue(
                home,
                listOf(
                    persisted("t-live", liveDir.toString()),
                    persisted("t-dead", stagingRoot.resolve("t-dead").toString()), // staging gone
                    persisted("t-done", stagingRoot.resolve("t-done").toString(), state = "DONE"),
                    persisted("t-cancelled", historyDir.toString(), state = "CANCELLED"),
                ),
            )

            val loaded = DesktopQueuePersistence(home).load()

            // non-terminal with missing staging dropped; terminal entries survive the prune
            assertEquals(listOf("t-live", "t-done", "t-cancelled"), loaded.map { it.id })
            // orphan sweep: a dir referenced only by a terminal entry is swept, the
            // non-terminal entry's staging survives
            assertTrue(liveDir.exists(), "live entry's staging must survive")
            assertFalse(historyDir.exists(), "staging referenced only by history must be swept")
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyQueueFileLoadsWithDefaultsAndKeepsNonTerminalPrune() = runTest {
        val home = createTempDirectory("surge-queue-test")
        try {
            val stagingRoot = home.resolve(".surgemusic").resolve("staging")
            val liveDir = stagingRoot.resolve("t-old")
            Files.createDirectories(liveDir)
            // pre-history format: no state/bytesTotal/note fields (paths need JSON escaping)
            val liveJsonPath = liveDir.toString().replace("\\", "\\\\")
            val goneJsonPath = stagingRoot.resolve("t-gone").toString().replace("\\", "\\\\")
            Files.createDirectories(home.resolve(".surgemusic"))
            Files.writeString(
                home.resolve(".surgemusic").resolve("queue.json"),
                """
                [
                  {"id":"t-old","topicId":6918211,"title":"Old","forumName":"Rock",
                   "torrentBytesBase64":null,"stagingPath":"$liveJsonPath","wantedFiles":[0,2]},
                  {"id":"t-gone","topicId":6918212,"title":"Gone","forumName":"Pop",
                   "torrentBytesBase64":null,"stagingPath":"$goneJsonPath","wantedFiles":[]}
                ]
                """.trimIndent(),
            )

            val loaded = DesktopQueuePersistence(home).load()

            assertEquals(
                listOf("t-old"),
                loaded.map { it.id },
                "vanished staging still prunes legacy (non-terminal) entries",
            )
            val entry = loaded.single()
            assertEquals("QUEUED", entry.state, "legacy entries default to the resumable queue")
            assertEquals(0L, entry.bytesTotal)
            assertEquals(null, entry.note)
            assertEquals(listOf(0, 2), entry.wantedFiles)
            assertFalse(entry.isTerminal)
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
