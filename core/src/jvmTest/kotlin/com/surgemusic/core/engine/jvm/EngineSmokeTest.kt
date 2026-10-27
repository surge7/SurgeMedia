package com.surgemusic.core.engine.jvm

import com.surgemusic.core.engine.EngineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.div
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class EngineSmokeTest {

    /**
     * Real libtorrent sessions over loopback — hardware/OS-sensitive. macOS runs them
     * (the shipped desktop platform); Linux CI runners segfault inside the native
     * session (exit 139), so they are skipped there unless CI_ENGINE_TESTS=1 opts in.
     */
    @BeforeTest
    fun assumeNativeEngineSupported() {
        val osName = System.getProperty("os.name").lowercase()
        val macOS = osName.contains("mac") || osName.contains("darwin")
        assumeTrue(
            "native engine smoke tests skipped on $osName (set CI_ENGINE_TESTS=1 to force)",
            macOS || System.getenv("CI_ENGINE_TESTS") == "1",
        )
    }

    /** The sealed interface itself has no `id` member; every variant does. */
    private val EngineEvent.id: String
        get() = when (this) {
            is EngineEvent.Added -> id
            is EngineEvent.Progress -> id
            is EngineEvent.Completed -> id
            is EngineEvent.Failed -> id
        }

    /**
     * Subscribes to the leech engine's events (BEFORE the download starts — SharedFlow
     * replay=0 drops events emitted while no collector is attached), starts the leech
     * (optionally with a wanted-file selection), and collects until the terminal event
     * for the leech id.
     */
    private fun collectUntilTerminal(
        harness: LoopbackHarness,
        start: () -> Unit = { harness.startLeech() },
    ): List<EngineEvent> =
        runBlocking {
            withTimeout(180_000) {
                val subscribed = CompletableDeferred<Unit>()
                val terminal = CompletableDeferred<EngineEvent>()
                val collected = mutableListOf<EngineEvent>()
                val job = launch {
                    harness.leechEngine.events
                        .onSubscription { subscribed.complete(Unit) }
                        .collect { ev ->
                            if (ev.id != LoopbackHarness.LEECH_ID) return@collect
                            collected.add(ev)
                            when (ev) {
                                is EngineEvent.Completed -> terminal.complete(ev)
                                is EngineEvent.Failed -> terminal.complete(ev)
                                is EngineEvent.Added, is EngineEvent.Progress -> Unit
                            }
                        }
                }
                subscribed.await()
                start()
                val last = terminal.await()
                job.cancel()
                assertTrue(last is EngineEvent.Completed, "terminal event: $last")
                collected
            }
        }

    @Test fun downloadEmitsProgressAndCompletesForLocalSeed() {
        // Uses the loopback harness: seed from a locally seeded session, leech on a second one.
        val harness = LoopbackHarness()
        try {
            harness.startSeed()
            val events = collectUntilTerminal(harness)
            assertTrue(
                events.any { it is EngineEvent.Progress && it.bytesTotal > 0 },
                "no Progress with bytesTotal>0: $events",
            )
            assertTrue(events.any { it is EngineEvent.Added }, "no Added event: $events")
            // Completed fires exactly once per id (guarded by the engine's completed set).
            assertEquals(1, events.count { it is EngineEvent.Completed }, "events=$events")
        } finally { harness.close() }
    }

    @Test fun downloadedFilesMatchSource() {
        val harness = LoopbackHarness()
        try {
            harness.startSeed()
            collectUntilTerminal(harness)
            val src = TestTorrentFactory.regularFilesUnder(harness.sourceDir)
            val dst = TestTorrentFactory.regularFilesUnder(harness.targetDir.resolve(harness.sourceDir.fileName.toString()))
            assertEquals(src, dst)
            src.forEach { name ->
                assertContentEquals(
                    (harness.sourceDir / name).toFile().readBytes(),
                    (harness.targetDir / harness.sourceDir.fileName / name).toFile().readBytes(),
                )
            }
        } finally { harness.close() }
    }

    /**
     * Selective download: wanting only file 0 (track01.mp3) must skip track02.mp3.
     * `totalWanted` counts whole pieces, so it is 700,000 rounded up to the 32 KiB piece
     * boundary (720,896) — not the full 1,000,000; the skipped file may receive at most
     * the boundary-piece spillover (< one piece), never its own content.
     */
    @Test fun selectiveDownloadFetchesOnlyWantedFiles() {
        val harness = LoopbackHarness()
        try {
            harness.startSeed()
            // Torrent files are indexed in TestTorrentFactory's list order: track01=0, track02=1.
            val events = collectUntilTerminal(harness) { harness.startLeech(wantedFiles = setOf(0)) }
            assertTrue(
                events.filterIsInstance<EngineEvent.Progress>().all { it.bytesTotal < 1_000_000L },
                "bytesTotal should reflect the wanted file (piece-rounded), not the whole torrent: $events",
            )
            val landedDir = harness.targetDir.resolve(harness.sourceDir.fileName.toString())
            assertContentEquals(
                (harness.sourceDir / "track01.mp3").toFile().readBytes(),
                (landedDir / "track01.mp3").toFile().readBytes(),
            )
            // The skipped file is at most the piece-boundary spillover — never its 300 KB.
            val skipped = (landedDir / "track02.mp3").toFile()
            assertTrue(
                !skipped.exists() || skipped.length() <= 32_768L,
                "skipped file has more than piece-boundary spillover: ${skipped.length()}",
            )
        } finally { harness.close() }
    }

    /**
     * "Add more files to a finished download" (restart path): after completing with only
     * file 0 wanted, a second start for the same id must remove the old torrent (no
     * duplicate-add rejection) and fetch the previously skipped file 1, then fire
     * Completed a second time (the completed-set bookkeeping resets on restart).
     */
    @Test fun extendingFinishedDownloadFetchesOnlyTheNewFiles() {
        val harness = LoopbackHarness()
        try {
            harness.startSeed()
            collectUntilTerminal(harness) { harness.startLeech(wantedFiles = setOf(0)) }
            val landed = harness.targetDir.resolve(harness.sourceDir.fileName.toString())
            assertContentEquals(
                (harness.sourceDir / "track01.mp3").toFile().readBytes(),
                (landed / "track01.mp3").toFile().readBytes(),
            )

            val secondRound = collectUntilTerminal(harness) { harness.startLeech(wantedFiles = setOf(0, 1)) }

            assertTrue(secondRound.any { it is EngineEvent.Added }, "restart must emit Added: $secondRound")
            assertEquals(1, secondRound.count { it is EngineEvent.Completed }, "events=$secondRound")
            // Already-finalized file 0 stays intact; the new file 1 arrives complete.
            assertContentEquals(
                (harness.sourceDir / "track01.mp3").toFile().readBytes(),
                (landed / "track01.mp3").toFile().readBytes(),
            )
            assertContentEquals(
                (harness.sourceDir / "track02.mp3").toFile().readBytes(),
                (landed / "track02.mp3").toFile().readBytes(),
            )
        } finally { harness.close() }
    }

    /**
     * "Add more files" on a live item (priority-update path): a running torrent's
     * selection is widened via [TorrentEngine.setWantedFiles] and the previously skipped
     * file downloads without a restart.
     */
    @Test fun livePriorityUpdateFetchesNewlyWantedFiles() {
        val harness = LoopbackHarness()
        try {
            harness.startSeed()
            collectUntilTerminal(harness) { harness.startLeech(wantedFiles = setOf(0)) }
            val landed = harness.targetDir.resolve(harness.sourceDir.fileName.toString())

            val secondRound = collectUntilTerminal(harness) {
                harness.leechEngine.setWantedFiles(LoopbackHarness.LEECH_ID, setOf(0, 1))
            }

            assertEquals(1, secondRound.count { it is EngineEvent.Completed }, "events=$secondRound")
            assertContentEquals(
                (harness.sourceDir / "track01.mp3").toFile().readBytes(),
                (landed / "track01.mp3").toFile().readBytes(),
            )
            assertContentEquals(
                (harness.sourceDir / "track02.mp3").toFile().readBytes(),
                (landed / "track02.mp3").toFile().readBytes(),
            )
        } finally { harness.close() }
    }
}
