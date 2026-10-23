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
     * replay=0 drops events emitted while no collector is attached), starts the leech,
     * and collects until the terminal event for the leech id.
     */
    private fun collectUntilTerminal(harness: LoopbackHarness): List<EngineEvent> =
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
                harness.startLeech()
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
}
