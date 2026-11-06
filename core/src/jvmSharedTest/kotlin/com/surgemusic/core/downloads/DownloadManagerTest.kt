package com.surgemusic.core.downloads

import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.TorrentFileInfo
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { item, staging ->
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
        // the finished engine torrent is torn down fire-and-forget (fix: dead handle
        // cleanup) — poll, it runs on the manager scope
        withTimeout(5_000) { while (!engine.cancelled.value.contains("t-1")) delay(10) }
    }

    @Test
    fun finalizeRunsOffConcurrencyCount(): Unit = runBlocking {
        val engine = FakeEngine()
        val finalizeGate = CompletableDeferred<Unit>()
        val finalizeStarted = CompletableDeferred<Unit>()
        val manager = DownloadManager(engine, MutableStateFlow(2)) { _, _ ->
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ ->
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
        val manager = DownloadManager(engine, MutableStateFlow(1)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(2)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        manager.enqueue(request("t-1"), torrent, "/staging/t-1")

        val failed = awaitItem(manager, "t-1") { it.state == DownloadState.FAILED }
        assertEquals("bad torrent bytes", failed.error)
    }

    @Test
    fun removeDoneItemDropsItAndKeepsOthers() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        // remove() tears down the dropped DONE item's engine torrent synchronously
        // (t-b's own fire-and-forget DONE teardown may cancel it too — expected now)
        assertTrue(engine.cancelled.value.contains("t-a"), "removing a terminal item tears down its engine torrent")
    }

    @Test
    fun removeDownloadingItemCancelsItAndDropsIt() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(2)) { _, _ -> Result.success("ok") }
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
        // terminal items' engine torrents are torn down too; the active pair is untouched
        assertTrue(
            engine.cancelled.value.toSet().containsAll(setOf("t-a", "t-b", "t-e")),
            "terminal items must be cancelled: ${engine.cancelled.value}",
        )
        assertTrue(!engine.cancelled.value.contains("t-c") && !engine.cancelled.value.contains("t-d"))
    }

    @Test
    fun removeUnknownIdIsNoOp() = runBlocking {
        val engine = FakeEngine()
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
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

    // ── DONE teardown vs restart (per-id generation) ─────────────────────────────────

    @Test
    fun teardownSkipsRestartedItem() = runBlocking {
        val fake = FakeEngine().apply { files = fourFiles }
        val engine = OrderingEngine(fake)
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
        fake.awaitSubscribed()
        manager.enqueue(request("t-1", sizeBytes = 100), torrent, "/staging/t-1", wantedFileIndices = setOf(0))
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertTrue(fake.events.tryEmit(EngineEvent.Completed("t-1")))
        awaitItem(manager, "t-1") { it.state == DownloadState.DONE }

        // Restart while the previous round's fire-and-forget DONE teardown is still in
        // flight: the restart bumps the id's generation, so the stale teardown must not
        // fire engine.cancel for the id anymore — a cancel landing after the fresh
        // engine.start would kill the restarted round. (The teardown may legitimately
        // cancel BEFORE the restart — that ordering is fine and not asserted against.)
        assertTrue(manager.addFiles("t-1", setOf(1, 2)))
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        withTimeout(5_000) { while (fake.started.value.size < 2) delay(10) }
        delay(250) // settle: a wrongly-issued teardown cancel would have landed by now

        val log = engine.log
        val restartAt = log.lastIndexOf("start:t-1")
        assertTrue(restartAt >= 0, "the restarted round must run: $log")
        assertTrue(
            log.drop(restartAt + 1).none { it == "cancel:t-1" },
            "stale DONE teardown must not cancel the restarted id: $log",
        )
        assertEquals(setOf(1, 2), fake.started.value[1].wantedFiles)
        assertEquals(DownloadState.DOWNLOADING, item(manager, "t-1").state)
    }

    @Test
    fun addFilesRestartStressTeardownNeverKillsFreshRound() = runBlocking {
        val fake = FakeEngine().apply { files = fourFiles }
        val engine = OrderingEngine(fake)
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
        fake.awaitSubscribed()
        manager.enqueue(request("t-1", sizeBytes = 100), torrent, "/staging/t-1", wantedFileIndices = setOf(0))

        repeat(40) { round ->
            awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
            assertTrue(fake.events.tryEmit(EngineEvent.Progress("t-1", bytesDone = 50, bytesTotal = 100, downloadSpeed = 10, peers = 2)))
            assertTrue(fake.events.tryEmit(EngineEvent.Completed("t-1")))
            awaitItem(manager, "t-1") { it.state == DownloadState.DONE }

            // Restart immediately, racing the previous round's fire-and-forget DONE
            // teardown: the restarted round must reach DOWNLOADING again (never left
            // CANCELLED), keep taking progress events, and no engine cancel may land
            // after its fresh start.
            assertTrue(manager.addFiles("t-1", if (round % 2 == 0) setOf(1) else setOf(2)), "round $round: restart refused")
            val restarted = awaitItem(manager, "t-1") {
                if (it.state == DownloadState.CANCELLED) error("round $round left CANCELLED: ${manager.items.value}")
                it.state == DownloadState.DOWNLOADING
            }
            assertEquals(0L, restarted.bytesDone, "a restarted round starts from zero")
            withTimeout(5_000) { while (fake.started.value.size < round + 2) delay(10) }
            assertTrue(
                engine.log.drop(engine.log.lastIndexOf("start:t-1") + 1).none { it == "cancel:t-1" },
                "round $round: stale DONE teardown cancelled the fresh start: ${engine.log}",
            )
            assertTrue(fake.events.tryEmit(EngineEvent.Progress("t-1", bytesDone = 60, bytesTotal = 200, downloadSpeed = 10, peers = 2)))
            awaitItem(manager, "t-1") { it.bytesDone == 60L }
        }
        delay(250) // settle: a wrongly-issued teardown cancel would have landed by now
        assertTrue(
            engine.log.drop(engine.log.lastIndexOf("start:t-1") + 1).none { it == "cancel:t-1" },
            "no cancel may follow the last fresh start: ${engine.log}",
        )
        assertEquals(DownloadState.DOWNLOADING, item(manager, "t-1").state)
    }

    // ── addFiles vs completion race (raced-to-DONE degrades into the union restart) ──

    /** Large file list: makes each active-state merge generation's union sum slow. */
    private val manyFiles: List<TorrentFileInfo> =
        List(100_000) { i -> TorrentFileInfo(index = i, path = "album/$i.flac", sizeBytes = 1) }

    /**
     * The completion racing a selection extension: addFiles' merge runs as a CAS loop
     * over the items list, so a completion landing mid-merge must not drop the new
     * files — when a retry snapshot sees DONE, the merge degrades into the DONE restart
     * wanting old+new. The machinery below holds the merge pending until the completion
     * has fully landed:
     * - the 100k-entry file list makes each active-state merge generation slow (the
     *   union sum), while a churn item's Progress handler mutates the list every
     *   collector cycle (~one plain map) — the merge's CAS therefore never finds a
     *   mutation-free attempt and always loses the ping-pong while mutations flow;
     * - thousands of filler items keep the collector's per-event cost high, so the
     *   FINALIZING→DONE window stays smaller than one merge generation.
     * The emitter is cancelled the moment the Completed event is accepted: the already
     * buffered Progress events keep mutating until the buffer drains, the two flips
     * then process back-to-back, and the merge's next retry snapshot sees DONE → union
     * restart. Should scheduling still produce another outcome, each is checked for
     * coherence (nothing dropped); at least one round must exercise the raced-to-DONE
     * union restart.
     */
    @Test
    fun addFilesRacingCompletionRestartsWithUnion() = runBlocking {
        var sawUnionRestart = false
        repeat(3) { round ->
            val fake = FakeEngine().apply { files = manyFiles }
            val manager = DownloadManager(fake, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
            fake.awaitSubscribed()
            // t-1 first: list order decides which QUEUED item a pump claims when a slot
            // frees up, so t-1's restart always beats the fillers into the engine.
            manager.enqueue(request("t-1", sizeBytes = 100), torrent, "/staging/t-1", wantedFileIndices = setOf(0))
            manager.enqueue(request("t-churn"), torrent, "/staging/t-churn")
            awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
            repeat(15_000) { manager.enqueue(request("f-$it"), torrent, "/staging/f-$it") }

            var churnBeat = 0L
            val churn = launch(Dispatchers.Default) {
                while (isActive) {
                    // bytesDone must vary: StateFlow conflates equal values, so a
                    // Progress payload identical to the last one would not commit a
                    // list change at all — and the merge would win every CAS attempt
                    if (
                        !fake.events.tryEmit(
                            EngineEvent.Progress("t-churn", bytesDone = ++churnBeat, bytesTotal = 2, downloadSpeed = 3, peers = 0),
                        )
                    ) {
                        delay(1) // buffer full — retry once the collector has drained a bit
                    }
                }
            }
            val addDeferred = async(Dispatchers.Default) { manager.addFiles("t-1", setOf(1, 2)) }
            delay(20) // the merge is now spinning, always losing the CAS ping-pong
            while (!fake.events.tryEmit(EngineEvent.Completed("t-1"))) delay(1)
            // stop the EMITTER now: the buffered Progress keeps mutating while it
            // drains, then the FINALIZING→DONE flips process back-to-back (their window
            // is one dispatch hop, far smaller than a merge generation)
            churn.cancel()
            awaitItem(manager, "t-1") { it.state == DownloadState.DONE }
            val added = addDeferred.await()
            // reclaim the churn item's slot so a pending restart can reach the engine
            manager.cancel("t-churn")
            val racerStarts = fake.started.value.filter { it.id == "t-1" }

            val stateDump = "items=${manager.items.value.take(6).map { it.request.id to it.state }} " +
                "starts=${fake.started.value.map { it.id }} rounds=$round"
            if (!added) {
                // raced only to FINALIZING — the sanctioned drop; the item settles DONE
                // with the engine untouched (the original start is the racer's only one)
                assertEquals(DownloadState.DONE, item(manager, "t-1").state, "round $round: $stateDump")
                assertEquals(1, racerStarts.size, "round $round: $stateDump")
                assertTrue(fake.priorityUpdates.value.isEmpty(), "round $round")
            } else if (fake.priorityUpdates.value.isNotEmpty()) {
                // the merge won: a live priority update carrying the union, no restart
                assertEquals(listOf("t-1" to setOf(0, 1, 2)), fake.priorityUpdates.value, "round $round")
                assertEquals(1, racerStarts.size, "round $round: $stateDump")
            } else {
                // a restart happened: the plain DONE path wants the new set only, the
                // raced-to-DONE path wants old+new — the union. The fresh round's
                // engine.start may still be in flight (the pump defers behind a busy
                // mutex to its backstop) — it is guaranteed once the churn slot above
                // freed, so await it before reading the call log instead of racing it.
                withTimeout(5_000) { while (fake.started.value.count { it.id == "t-1" } < 2) delay(10) }
                val restartStarts = fake.started.value.filter { it.id == "t-1" }
                assertEquals(2, restartStarts.size, "round $round: $stateDump")
                val wanted = restartStarts.last().wantedFiles
                assertTrue(wanted == setOf(1, 2) || wanted == setOf(0, 1, 2), "round $round: ${restartStarts}")
                if (wanted == setOf(0, 1, 2)) {
                    sawUnionRestart = true
                    awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
                    assertEquals(setOf(0, 1, 2), manager.wantedFiles("t-1"), "round $round")
                    assertEquals(3L, item(manager, "t-1").request.sizeBytes, "round $round")
                    assertTrue(fake.priorityUpdates.value.isEmpty(), "round $round")
                }
            }
        }
        assertTrue(sawUnionRestart, "no round exercised the raced-to-DONE union restart")
    }

    // ── cancel vs pump claim-and-start (the per-id mutex closes the orphan window) ───

    /**
     * [FakeEngine] wrapper whose [start] blocks briefly — widens the pump's
     * claim-and-start span (the mutex-held window) so a cancel fired mid-span really
     * lands in it; without the wrapper the window is nanoseconds and this stress would
     * never exercise the ordering it exists to verify.
     */
    private class SlowStartEngine(
        private val fake: FakeEngine,
        private val startDelayMs: Long,
    ) : TorrentEngine by fake {
        override fun start(id: String, torrentBytes: ByteArray, targetDir: String, wantedFiles: Set<Int>) {
            Thread.sleep(startDelayMs)
            fake.start(id, torrentBytes, targetDir, wantedFiles)
        }
    }

    @Test
    fun cancelVsPumpStartStressNeverLeavesRunningTorrent() = runBlocking {
        val fake = FakeEngine()
        val engine = OrderingEngine(SlowStartEngine(fake, startDelayMs = 25))
        val manager = DownloadManager(engine, MutableStateFlow(3)) { _, _ -> Result.success("ok") }
        fake.awaitSubscribed()

        repeat(40) { round ->
            val id = "t-$round"
            val enqueue = launch(Dispatchers.Default) { manager.enqueue(request(id), torrent, "/staging/$id") }
            // cancel as soon as the item exists — by then the pump is usually inside its
            // claim-and-start span (SlowStartEngine holds it for ~25 ms), so the cancel
            // lands in the window the per-id mutex must close
            awaitItem(manager, id) { true }
            manager.cancel(id)
            enqueue.join()
            // a contended cancel defers its engine.cancel to the manager scope
            withTimeout(5_000) { while (!fake.cancelled.value.contains(id)) delay(10) }
            assertEquals(DownloadState.CANCELLED, item(manager, id).state, "round $round: ${manager.items.value}")
            // no running torrent behind the CANCELLED item: at most one start, and no
            // start after the final cancel — when the pump won the race, its started
            // torrent must have been torn down by the serialized engine.cancel after it
            val log = engine.log
            val lastCancel = log.lastIndexOf("cancel:$id")
            assertTrue(lastCancel >= 0, "round $round: engine cancel missing: $log")
            assertTrue(log.count { it == "start:$id" } <= 1, "round $round: $log")
            assertTrue(
                log.drop(lastCancel + 1).none { it == "start:$id" },
                "round $round: start landed after the final cancel (running torrent behind CANCELLED item): $log",
            )
        }
    }

    // ── Queue persistence (crash/exit recovery) ──────────────────────────────────────

    @Test
    fun persistenceRestoresNonTerminalEntriesAsQueued() = runBlocking {
        val engine = FakeEngine()
        val store = FakeQueuePersistence(
            loaded = listOf(
                PersistedDownload("t-1", 6918211L, "A", "Rock", Base64.encode(torrent), "/staging/t-1", listOf(0)),
                // no torrent bytes → nothing for the engine to restart on → dropped
                PersistedDownload("t-2", 6918212L, "B", "Pop", null, "/staging/t-2", emptyList()),
            ),
        )
        val manager = DownloadManager(engine, MutableStateFlow(3), store) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }

        assertEquals(listOf("t-1"), manager.items.value.map { it.request.id })
        val call = engine.started.value.single()
        assertEquals("t-1", call.id)
        assertEquals("/staging/t-1", call.targetDir)
        assertEquals(setOf(0), call.wantedFiles)
        assertContentEquals(torrent, call.torrentBytes)
        assertEquals(setOf(0), manager.wantedFiles("t-1"))
    }

    @Test
    fun persistenceSavesOnEnqueueAndTerminalTransitions() = runBlocking {
        val engine = FakeEngine()
        val store = FakeQueuePersistence()
        val manager = DownloadManager(engine, MutableStateFlow(3), store) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        manager.enqueue(request("t-1"), torrent, "/staging/t-1")
        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        withTimeout(5_000) { while (store.saves.value.isEmpty()) delay(10) }
        assertEquals(
            listOf("t-1"),
            store.saves.value.last().map { it.id },
            "enqueue must flush the queue",
        )
        assertEquals("/staging/t-1", store.saves.value.last().single().stagingPath)

        // terminal transition (DONE): the item stays persisted as bounded history —
        // state/size/note ride along so the Downloads tab survives a restart
        assertTrue(engine.events.tryEmit(EngineEvent.Completed("t-1")))
        awaitItem(manager, "t-1") { it.state == DownloadState.DONE }
        withTimeout(5_000) {
            while (store.saves.value.last().firstOrNull { it.id == "t-1" }?.state != "DONE") delay(10)
        }
        val saved = store.saves.value.last().single()
        assertEquals("t-1", saved.id)
        assertEquals("DONE", saved.state)
        assertEquals(1_000L, saved.bytesTotal)
        assertEquals("ok", saved.note)
        assertNull(saved.torrentBytesBase64, "terminal history carries no torrent bytes")

        // removal prunes the persisted history the same way as the in-memory list
        manager.remove("t-1")
        withTimeout(5_000) { while (store.saves.value.last().containsPersisted("t-1")) delay(10) }
        assertTrue(store.saves.value.last().isEmpty(), "removed terminal item must drop out: ${store.saves.value.last()}")
    }

    @Test
    fun corruptedOrEmptyStoreStartsClean() = runBlocking {
        val engine = FakeEngine()
        val manager =
            DownloadManager(engine, MutableStateFlow(3), FakeQueuePersistence(loadThrows = true)) { _, _ ->
                Result.success("ok")
            }
        engine.awaitSubscribed()

        manager.enqueue(request("t-1"), torrent, "/staging/t-1")

        awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertEquals(listOf("t-1"), manager.items.value.map { it.request.id })
        assertEquals(1, engine.started.value.size)
    }

    // ── Restored terminal history (bounded, no revival) ──────────────────────────────

    @Test
    fun persistenceRestoresTerminalEntriesWithoutEngineRevival() = runBlocking {
        val engine = FakeEngine()
        val store = FakeQueuePersistence(
            loaded = listOf(
                PersistedDownload(
                    "t-done", 6918211L, "Done Album", "Rock", null, "/staging/t-done", emptyList(),
                    state = "DONE", bytesTotal = 1_234, note = "2 files → Music/SurgeMusic/Done Album",
                ),
                PersistedDownload(
                    "t-failed", 6918212L, "Failed Album", "Pop", null, "", emptyList(),
                    state = "FAILED", bytesTotal = 500, note = "mediastore insert failed",
                ),
                PersistedDownload(
                    "t-live", 6918213L, "Live Album", "Jazz", Base64.encode(torrent), "/staging/t-live",
                    listOf(0), bytesTotal = 777,
                ),
            ),
        )
        val manager = DownloadManager(engine, MutableStateFlow(3), store) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        awaitItem(manager, "t-live") { it.state == DownloadState.DOWNLOADING }

        // terminal history restored verbatim — state, size and note, in the right field
        val done = item(manager, "t-done")
        assertEquals(DownloadState.DONE, done.state)
        assertEquals(1_234L, done.bytesTotal)
        assertEquals("2 files → Music/SurgeMusic/Done Album", done.outputNote)
        assertNull(done.error)
        val failed = item(manager, "t-failed")
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(500L, failed.bytesTotal)
        assertEquals("mediastore insert failed", failed.error)
        assertNull(failed.outputNote)
        // no engine/staging revival: only the non-terminal entry starts, history entries
        // get no staged start and no teardown churn
        assertEquals(listOf("t-live"), engine.started.value.map { it.id })
        assertTrue(engine.cancelled.value.isEmpty(), "restored history must not touch the engine")
        assertTrue(manager.wantedFiles("t-done").isEmpty())
        // restored non-terminal item keeps its persisted size before the first Progress tick
        assertEquals(777L, item(manager, "t-live").bytesTotal)
    }

    @Test
    fun reEnqueueOverRestoredTerminalReplacesAndRestarts() = runBlocking {
        val engine = FakeEngine()
        val store = FakeQueuePersistence(
            loaded = listOf(
                PersistedDownload(
                    "t-1", 6918211L, "A", "Rock", null, "/staging/t-1", emptyList(),
                    state = "DONE", bytesTotal = 900, note = "ok",
                ),
            ),
        )
        val manager = DownloadManager(engine, MutableStateFlow(3), store) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()
        awaitItem(manager, "t-1") { it.state == DownloadState.DONE }
        assertEquals(0, engine.started.value.size)

        manager.enqueue(request("t-1"), torrent, "/staging/t-1") // same id → replaces the history entry

        val restarted = awaitItem(manager, "t-1") { it.state == DownloadState.DOWNLOADING }
        assertEquals(1, manager.items.value.count { it.request.id == "t-1" })
        assertEquals(1, engine.started.value.size, "re-enqueue over restored history starts a fresh round")
        assertEquals("/staging/t-1", engine.started.value.single().targetDir)
        assertEquals(1_000L, restarted.bytesTotal, "the fresh round uses the new request's size")
        // the next save persists it as resumable again — with torrent bytes, not DONE history
        withTimeout(5_000) { while (store.saves.value.lastOrNull() == null) delay(10) }
        val saved = store.saves.value.last().single { it.id == "t-1" }
        assertNotNull(saved.torrentBytesBase64, "a re-enqueued round persists with torrent bytes again")
        assertEquals(1_000L, saved.bytesTotal)
        assertTrue(saved.state != "DONE", "no longer persisted as terminal history: ${saved.state}")
    }

    @Test
    fun persistedHistoryCapsAtNewestHundredTerminalItems() = runBlocking {
        val engine = FakeEngine()
        val store = FakeQueuePersistence()
        val manager = DownloadManager(engine, MutableStateFlow(3), store) { _, _ -> Result.success("ok") }
        engine.awaitSubscribed()

        repeat(120) { manager.enqueue(request("t-$it"), torrent, "/staging/t-$it") }
        // Failed transitions QUEUED/DOWNLOADING/PAUSED alike — all 120 become terminal in
        // event order = completion order (t-0 oldest … t-119 newest)
        repeat(120) { assertTrue(engine.events.tryEmit(EngineEvent.Failed("t-$it", "boom"))) }
        awaitItem(manager, "t-119") { it.state == DownloadState.FAILED }
        // deterministic final flush: one fresh active item rides in front of the history;
        // every save from here on snapshots the same settled state
        manager.enqueue(request("t-final"), torrent, "/staging/t-final")
        withTimeout(5_000) {
            while (store.saves.value.lastOrNull()?.containsPersisted("t-final") != true) delay(10)
        }

        val snapshot = store.saves.value.last()
        assertEquals("t-final", snapshot.first().id, "the active queue precedes the history")
        val terminalIds = snapshot.filter { it.state == "FAILED" }.map { it.id }
        assertEquals(100, terminalIds.size, "history caps at 100: ${snapshot.map { it.state }}")
        assertEquals(
            (20..119).map { "t-$it" },
            terminalIds,
            "the oldest beyond the cap drop; order stays oldest→newest",
        )
    }
}

/** Records save calls; [loaded] scripts load (a throw simulates a corrupt store). */
private class FakeQueuePersistence(
    private val loaded: List<PersistedDownload> = emptyList(),
    private val loadThrows: Boolean = false,
) : QueuePersistence {
    val saves = MutableStateFlow<List<List<PersistedDownload>>>(emptyList())

    override suspend fun load(): List<PersistedDownload> {
        if (loadThrows) throw IllegalStateException("corrupt queue.json")
        return loaded
    }

    override suspend fun save(items: List<PersistedDownload>) {
        saves.value += listOf(items)
    }
}

private fun List<PersistedDownload>.containsPersisted(id: String): Boolean = any { it.id == id }

/**
 * [TorrentEngine] wrapper logging start/cancel chronologically — FakeEngine's per-call
 * lists cannot express call ORDER, which order-sensitive tests (e.g. "the stale DONE
 * teardown must not cancel after a restart's start") need. Everything else delegates.
 */
private class OrderingEngine(private val fake: TorrentEngine) : TorrentEngine by fake {
    private val logFlow = MutableStateFlow<List<String>>(emptyList())

    /** Engine start/cancel calls as "start:<id>" / "cancel:<id>", in call order. */
    val log: List<String> get() = logFlow.value

    override fun start(id: String, torrentBytes: ByteArray, targetDir: String, wantedFiles: Set<Int>) {
        logFlow.update { it + "start:$id" }
        fake.start(id, torrentBytes, targetDir, wantedFiles)
    }

    override fun cancel(id: String) {
        logFlow.update { it + "cancel:$id" }
        fake.cancel(id)
    }
}
