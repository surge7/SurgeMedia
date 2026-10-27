package com.surgemusic.core.downloads

import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentFileInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadManagerTest {

    private val torrent = "d8:announce29:https://bt.t-ru.org/announce4:infod6:lengthi5eee".toByteArray()

    private fun request(id: String, sizeBytes: Long = 1_000L) = DownloadRequest(
        id = id,
        topicId = 6918211L,
        title = "Artist - Album (2020) [FLAC]",
        forumName = "Rock",
        sizeBytes = sizeBytes,
    )

    /** The manager subscribes to engine events from its own scope; wait for the subscription. */
    private suspend fun FakeEngine.awaitSubscribed() {
        withTimeout(5_000) { events.subscriptionCount.first { it >= 1 } }
    }

    private fun item(manager: DownloadManager, id: String): DownloadItem =
        assertNotNull(manager.items.value.firstOrNull { it.request.id == id }, "no item $id in ${manager.items.value}")

    /** Suspends until [id] matches [predicate]; returns the matching snapshot. */
    private suspend fun awaitItem(
        manager: DownloadManager,
        id: String,
        predicate: (DownloadItem) -> Boolean,
    ): DownloadItem {
        var match: DownloadItem? = null
        withTimeout(5_000) {
            manager.items.first { list ->
                list.firstOrNull { it.request.id == id }?.let {
                    if (predicate(it)) {
                        match = it
                        true
                    } else {
                        false
                    }
                } == true
            }
        }
        return assertNotNull(match, "item $id never matched; items=${manager.items.value}")
    }

    @Test
    fun enqueuedItemStartsWhenSlotFree() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        manager.enqueue(request("t-1"), torrent, "/staging/t-1")

        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertEquals(1, engine.started.value.size)
        val call = engine.started.value.single()
        assertEquals("t-1", call.id)
        assertContentEquals(torrent, call.torrentBytes)
        assertEquals("/staging/t-1", call.targetDir)
    }

    @Test
    fun wantedFileSelectionIsForwardedToEngineStart() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        manager.enqueue(request("t-1"), torrent, "/staging/t-1", wantedFileIndices = setOf(0, 2))

        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertEquals(setOf(0, 2), engine.started.value.single().wantedFiles)

        // Default (no selection) reaches the engine as "everything".
        manager.clearCompleted()
        manager.remove("t-1")
        manager.enqueue(request("t-2"), torrent, "/staging/t-2")
        awaitItem(manager, "t-2") { it.state == DownloadState.DOWNLOADING }
        assertEquals(emptySet(), engine.started.value.last().wantedFiles)
    }

    @Test
    fun concurrencyLimitHoldsAtMaxConcurrent() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        repeat(5) { manager.enqueue(request("t-$it"), torrent, "/staging/t-$it") }

        assertEquals(3, engine.started.value.size)
        assertEquals(
            listOf(
                DownloadState.DOWNLOADING,
                DownloadState.DOWNLOADING,
                DownloadState.DOWNLOADING,
                DownloadState.QUEUED,
                DownloadState.QUEUED,
            ),
            manager.items.value.map { it.state },
        )
    }

    @Test
    fun progressEventUpdatesItem() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1", sizeBytes = 1_000), torrent, "/staging/t-1")

        assertTrue(engine.events.tryEmit(EngineEvent.Progress("t-1", bytesDone = 250, bytesTotal = 1_000, downloadSpeed = 42, peers = 7)))

        val item = awaitItem(manager, "t-1") { it.bytesDone == 250L }
        assertEquals(DownloadState.DOWNLOADING, item.state)
        assertEquals(1_000L, item.bytesTotal)
        assertEquals(42L, item.downloadSpeed)
        assertEquals(7, item.peers)
        assertNull(item.error)
    }

    @Test
    fun completedItemFinalizesThenDone() = runBlocking {
        val engine = FakeEngine()
        val finalizeGate = CompletableDeferred<Unit>()
        val finalizeArgs = CompletableDeferred<Pair<DownloadItem, String>>()
        val manager = DownloadManager(engine, 3) { item, staging ->
            finalizeArgs.complete(item to staging)
            finalizeGate.await()
            Result.success("2 files → Music/SurgeMusic/Artist - Album")
        }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")

        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-1")))

        val (finalizedItem, staging) = withTimeout(5_000) { finalizeArgs.await() }
        assertEquals("t-1", finalizedItem.request.id)
        assertEquals("/staging/t-1", staging)
        assertEquals(DownloadState.FINALIZING, item(manager, "t-1").state)

        finalizeGate.complete(Unit)
        val done = awaitItem(manager, "t-1") { it.state == DownloadState.DONE }
        assertEquals("2 files → Music/SurgeMusic/Artist - Album", done.outputNote)
        assertNull(done.error)
    }

    @Test
    fun finalizeRunsOffConcurrencyCount(): Unit = runBlocking {
        val engine = FakeEngine()
        val finalizeGate = CompletableDeferred<Unit>()
        val finalizeStarted = CompletableDeferred<Unit>()
        val manager = DownloadManager(engine, 2) { _, _ ->
            finalizeStarted.complete(Unit)
            finalizeGate.await()
            Result.success("ok")
        }
        engine.awaitSubscribed()
        manager.enqueue(request("t-a"), torrent, "/staging/t-a")
        manager.enqueue(request("t-b"), torrent, "/staging/t-b")
        manager.enqueue(request("t-c"), torrent, "/staging/t-c")
        assertEquals(2, engine.started.value.size)
        assertEquals(DownloadState.QUEUED, item(manager, "t-c").state)

        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-a")))
        withTimeout(5_000) { finalizeStarted.await() }

        // t-a is stuck inside finalize (gate closed) and holds no slot, so t-c starts now.
        awaitItem(manager, "t-c") { it.state == DownloadState.DOWNLOADING }
        withTimeout(5_000) { while (engine.started.value.size < 3) delay(10) }
        assertEquals(DownloadState.FINALIZING, item(manager, "t-a").state)

        finalizeGate.complete(Unit)
        awaitItem(manager, "t-a") { it.state == DownloadState.DONE }
    }

    @Test
    fun finalizeFailureMarksFailed() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ ->
            Result.failure(IllegalStateException("mediastore insert failed"))
        }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")

        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-1")))

        val item = awaitItem(manager, "t-1") { it.state == DownloadState.FAILED }
        assertEquals("mediastore insert failed", item.error)
        assertNull(item.outputNote)
    }

    @Test
    fun failedEventMarksItemFailed(): Unit = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 1) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")
        manager.enqueue(request("t-2"), torrent, "/staging/t-2") // QUEUED while t-1 downloads
        assertEquals(DownloadState.QUEUED, item(manager, "t-2").state)

        assertTrue(engine.events.tryEmit(EngineEvent.Failed("t-1", "torrent handle not added within timeout")))

        val failed = awaitItem(manager, "t-1") { it.state == DownloadState.FAILED }
        assertEquals("torrent handle not added within timeout", failed.error)
        // the freed slot lets the queued item start
        awaitItem(manager, "t-2") { it.state == DownloadState.DOWNLOADING }
    }

    @Test
    fun cancelFreesSlotForNextQueued() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        repeat(3) { manager.enqueue(request("t-$it"), torrent, "/staging/t-$it") }
        manager.enqueue(request("t-3"), torrent, "/staging/t-3")
        assertEquals(3, engine.started.value.size)
        assertEquals(DownloadState.QUEUED, item(manager, "t-3").state)

        manager.cancel("t-0")

        assertEquals(DownloadState.CANCELLED, item(manager, "t-0").state)
        assertTrue(engine.cancelled.value.contains("t-0"))
        awaitItem(manager, "t-3") { it.state == DownloadState.DOWNLOADING }
        assertEquals(4, engine.started.value.size)
    }

    @Test
    fun pausePauseAllTransitions() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 2) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-a"), torrent, "/staging/t-a")
        manager.enqueue(request("t-b"), torrent, "/staging/t-b")
        manager.enqueue(request("t-c"), torrent, "/staging/t-c") // QUEUED
        assertEquals(2, engine.started.value.size)

        manager.pause("t-a") // does not free a slot
        assertEquals(DownloadState.PAUSED, item(manager, "t-a").state)
        assertTrue(engine.paused.value.contains("t-a"))
        assertEquals(DownloadState.QUEUED, item(manager, "t-c").state)
        assertEquals(2, engine.started.value.size)

        manager.resume("t-a")
        assertEquals(DownloadState.DOWNLOADING, item(manager, "t-a").state)
        assertTrue(engine.resumed.value.contains("t-a"))

        manager.pauseAll()
        assertEquals(DownloadState.PAUSED, item(manager, "t-a").state)
        assertEquals(DownloadState.PAUSED, item(manager, "t-b").state)
        assertEquals(DownloadState.QUEUED, item(manager, "t-c").state)
        assertEquals(setOf("t-a", "t-b"), engine.paused.value.toSet())
    }

    @Test
    fun duplicateEnqueueWhileInFlightIsIgnored() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")
        manager.enqueue(request("t-1"), torrent, "/staging/t-1") // second tap while DOWNLOADING

        assertEquals(1, manager.items.value.count { it.request.id == "t-1" })
        assertEquals(1, engine.started.value.size)
        assertEquals(DownloadState.DOWNLOADING, item(manager, "t-1").state)
    }

    @Test
    fun reEnqueueAfterFailureResetsItemAndRestarts() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")
        assertTrue(engine.events.tryEmit(EngineEvent.Failed("t-1", "tracker dead")))
        awaitItem(manager, "t-1") { it.state == DownloadState.FAILED }

        manager.enqueue(request("t-1"), torrent, "/staging/t-1") // retry same topic

        assertEquals(1, manager.items.value.count { it.request.id == "t-1" })
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertEquals(2, engine.started.value.size)
    }

    @Test
    fun throwingEngineStartFailsItemInsteadOfCrashingCaller() = runBlocking {
        val engine = FakeEngine().apply { throwOnStart = IllegalStateException("bad torrent bytes") }
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        manager.enqueue(request("t-1"), torrent, "/staging/t-1")

        val failed = awaitItem(manager, "t-1") { it.state == DownloadState.FAILED }
        assertEquals("bad torrent bytes", failed.error)
    }

    @Test
    fun removeDoneItemDropsItAndKeepsOthers() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-a"), torrent, "/staging/t-a")
        manager.enqueue(request("t-b"), torrent, "/staging/t-b")
        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-a")))
        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-b")))
        awaitItem(manager, "t-a") { it.state == DownloadState.DONE }
        awaitItem(manager, "t-b") { it.state == DownloadState.DONE }

        manager.remove("t-a")

        assertEquals(listOf("t-b"), manager.items.value.map { it.request.id })
        assertEquals(DownloadState.DONE, item(manager, "t-b").state)
        assertTrue(engine.cancelled.value.isEmpty(), "removing a terminal item must not cancel anything")
    }

    @Test
    fun removeDownloadingItemCancelsItAndDropsIt() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }

        manager.remove("t-1")

        assertTrue(engine.cancelled.value.contains("t-1"), "removing an active item must cancel the engine side")
        assertTrue(manager.items.value.none { it.request.id == "t-1" })
    }

    @Test
    fun clearCompletedKeepsActiveAndDropsTerminalItems() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 2) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        // a, b take the 2 slots; c, d, e stay QUEUED
        listOf("t-a", "t-b", "t-c", "t-d", "t-e").forEach { manager.enqueue(request(it), torrent, "/staging/$it") }
        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-a")))
        awaitItem(manager, "t-a") { it.state == DownloadState.DONE }
        assertTrue(engine.events.tryEmit(EngineEvent.Failed("t-b", "boom")))
        awaitItem(manager, "t-b") { it.state == DownloadState.FAILED }
        manager.cancel("t-e") // QUEUED is active → CANCELLED; that slot's pump has nothing queued left
        assertEquals(DownloadState.CANCELLED, item(manager, "t-e").state)
        awaitItem(manager, "t-c") { it.state == DownloadState.DOWNLOADING }
        awaitItem(manager, "t-d") { it.state == DownloadState.DOWNLOADING }

        manager.clearCompleted()

        val after = manager.items.value
        assertEquals(setOf("t-c", "t-d"), after.map { it.request.id }.toSet())
        assertTrue(after.all { it.state == DownloadState.DOWNLOADING }, "active items must survive: ${after.map { it.state }}")
        assertTrue(engine.cancelled.value.contains("t-e") && !engine.cancelled.value.contains("t-c"))
    }

    @Test
    fun removeUnknownIdIsNoOp() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1")
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        val before = manager.items.value

        manager.remove("nope")
        manager.clearCompleted() // only t-1 exists and it is active → also a no-op here

        assertEquals(before, manager.items.value)
        assertTrue(engine.cancelled.value.isEmpty())
    }

    // ── Add-files flows (extending a torrent's selection with more folders) ──────────

    private val fourFiles = listOf(
        TorrentFileInfo(0, "Album A/01.flac", 100),
        TorrentFileInfo(1, "Album A/02.flac", 200),
        TorrentFileInfo(2, "Album B/01.flac", 400),
        TorrentFileInfo(3, "cover.jpg", 50),
    )

    @Test
    fun stagedFilesAndWantedSetAreExposedAfterEnqueue() = runBlocking {
        val engine = FakeEngine().apply { files = fourFiles }
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1"), torrent, "/staging/t-1", wantedFileIndices = setOf(0, 2))

        assertEquals(fourFiles, manager.torrentFiles("t-1"))
        assertEquals(setOf(0, 2), manager.wantedFiles("t-1"))
        assertTrue(manager.torrentFiles("nope").isEmpty(), "unknown id exposes no files")
        assertTrue(manager.wantedFiles("nope").isEmpty())
    }

    @Test
    fun addFilesOnDownloadingMergesAndRepointsEnginePriorities() = runBlocking {
        val engine = FakeEngine().apply { files = fourFiles }
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1", sizeBytes = 500), torrent, "/staging/t-1", wantedFileIndices = setOf(0, 2))
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }

        val added = manager.addFiles("t-1", setOf(3))

        assertTrue(added)
        assertEquals(listOf("t-1" to setOf(0, 2, 3)), engine.priorityUpdates.value)
        assertEquals(setOf(0, 2, 3), manager.wantedFiles("t-1"))
        // the item's total now covers the union (100 + 400 + 50)
        assertEquals(550L, item(manager, "t-1").bytesTotal)
        assertEquals(1, engine.started.value.size, "a live merge must not restart the engine")
    }

    @Test
    fun addFilesOnDoneRestartsWantingOnlyNewFiles() = runBlocking {
        val engine = FakeEngine().apply { files = fourFiles }
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        manager.enqueue(request("t-1", sizeBytes = 100), torrent, "/staging/t-1", wantedFileIndices = setOf(0))
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-1")))
        awaitItem(manager, "t-1") { it.state == DownloadState.DONE }

        val added = manager.addFiles("t-1", setOf(1, 2))

        assertTrue(added)
        val restarted = awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertEquals(600L, restarted.request.sizeBytes, "size reflects the new selection only")
        assertEquals(2, engine.started.value.size, "DONE extends via engine.start, not a priority update")
        assertEquals(setOf(1, 2), engine.started.value[1].wantedFiles)
        assertEquals(setOf(1, 2), manager.wantedFiles("t-1"))
        assertTrue(engine.priorityUpdates.value.isEmpty())
    }

    @Test
    fun addFilesNoopsWhenEverythingIsAlreadyWanted() = runBlocking {
        val engine = FakeEngine().apply { files = fourFiles }
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        // explicit full selection
        manager.enqueue(request("t-1"), torrent, "/staging/t-1", wantedFileIndices = setOf(0, 1, 2, 3))
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertFalse(manager.addFiles("t-1", setOf(3)))
        // implicit full selection (empty wanted set = all files) on a DONE item
        manager.enqueue(request("t-2"), torrent, "/staging/t-2")
        awaitItem(manager, "t-2") { it.state == DownloadState.DOWNLOADING }
        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-2")))
        awaitItem(manager, "t-2") { it.state == DownloadState.DONE }
        assertFalse(manager.addFiles("t-2", setOf(1)))
        assertTrue(engine.priorityUpdates.value.isEmpty())
    }

    @Test
    fun addFilesNoopsOnUnknownIdAndUnextensibleStates() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, 3) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        assertFalse(manager.addFiles("nope", setOf(0)))
        manager.enqueue(request("t-1"), torrent, "/staging/t-1", wantedFileIndices = setOf(0))
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertTrue(engine.events.tryEmit(EngineEvent.Failed("t-1", "boom")))
        awaitItem(manager, "t-1") { it.state == DownloadState.FAILED }
        assertFalse(manager.addFiles("t-1", setOf(1)))
        assertFalse(manager.addFiles("t-1", emptySet()))
        assertTrue(engine.priorityUpdates.value.isEmpty())
        assertEquals(1, engine.started.value.size)
    }
}
