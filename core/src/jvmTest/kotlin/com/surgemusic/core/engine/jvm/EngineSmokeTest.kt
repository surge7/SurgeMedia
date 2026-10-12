package com.surgemusic.core.engine.jvm

import com.surgemusic.core.engine.EngineEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineSmokeTest {

    @Test fun downloadEmitsProgressAndCompletesForLocalSeed() {
        // Uses the loopback harness: seed from a locally seeded session, leech on a second one.
        val harness = LoopbackHarness()
        try {
            val events = runBlocking {
                withTimeout(180_000) {
                    harness.leecherDownload().transformWhile { e ->
                        emit(e)
                        e !is EngineEvent.Completed && e !is EngineEvent.Failed
                    }.toList()
                }
            }
            assertTrue(events.last() is EngineEvent.Completed, "last=$events")
            assertTrue(events.any { it is EngineEvent.Progress && it.bytesTotal > 0 })
        } finally { harness.close() }
    }

    @Test fun downloadedFilesMatchSource() {
        val harness = LoopbackHarness()
        try {
            runBlocking {
                withTimeout(180_000) {
                    harness.leecherDownload().collect { }
                }
            }
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
