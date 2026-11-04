package com.surgemusic.core.downloads

import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.TorrentFileInfo
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, FINALIZING, DONE, FAILED, CANCELLED }

data class DownloadRequest(
    val id: String, // engine id, e.g. "t-6918211"
    val topicId: Long,
    val title: String,
    val forumName: String,
    val sizeBytes: Long,
)

data class DownloadItem(
    val request: DownloadRequest,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = request.sizeBytes,
    val downloadSpeed: Long = 0,
    val peers: Int = 0,
    val error: String? = null,
    val outputNote: String? = null, // set on DONE: where files landed
)

/**
 * In-memory download queue and state machine over [TorrentEngine], with optional
 * crash/exit recovery via [QueuePersistence]: non-terminal items are re-enqueued as
 * QUEUED on init (torrent bytes ride along Base64-encoded).
 *
 * Slot accounting: an item holds a concurrency slot while DOWNLOADING or PAUSED
 * (pause does not free the slot; cancel/failure do). FINALIZING runs off the
 * concurrency count — the slot is freed when the engine reports Completed.
 *
 * @param maxConcurrent live slot limit — read per pump iteration, so a Settings change
 *   takes effect without rebuilding the manager (the stores keep the 1–5 clamp; the
 *   pump clamps again at read as a belt-and-braces).
 * @param persistence optional queue store; null keeps the manager purely in-memory.
 */
class DownloadManager(
    private val engine: TorrentEngine,
    private val maxConcurrent: StateFlow<Int> = MutableStateFlow(DEFAULT_MAX_CONCURRENT),
    private val persistence: QueuePersistence? = null,
    private val finalize: suspend (item: DownloadItem, stagingDir: String) -> Result<String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pumpMutex = Mutex()

    /** Serializes queue-store writes; saves are fire-and-forget on [scope]. */
    private val persistenceMutex = Mutex()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private data class StagedStart(
        val torrentBytes: ByteArray,
        val stagingDir: String,
        val wantedFiles: Set<Int> = emptySet(),
        /** Parsed at enqueue ([TorrentEngine.parseFiles]) so chooser flows need no re-parse. */
        val files: List<TorrentFileInfo> = emptyList(),
    )

    /** engine.start arguments per id — kept out of the public [DownloadItem]. */
    private val staged = MutableStateFlow<Map<String, StagedStart>>(emptyMap())

    /**
     * Per-id generation, bumped whenever a new round takes over an existing id — a
     * re-enqueue of a terminal item and addFiles' DONE-restart. The DONE teardown
     * captures the generation while the item is still FINALIZING (no bump path can run
     * against a non-terminal id) and cancels only when it is unchanged at teardown time:
     * without it the fire-and-forget teardown of a finished round could land after a
     * restart re-adopted the id and kill the fresh engine.start. Together with the
     * per-id start/teardown mutex (see [idLocks]) the check-then-cancel window is fully
     * closed.
     */
    private val generations = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * Per-id mutexes serializing an id's engine.start against its fire-and-forget DONE
     * teardown, created on demand and kept forever (bounded by distinct ids). Lock
     * hierarchy: manager state writes are non-suspending CAS updates that never touch
     * this lock — it is acquired only after the relevant state update commits, never
     * inside an update lambda — and [teardownEngine] holds the only suspending
     * acquisition ([Mutex.withLock]); the pump takes it non-suspending via
     * [Mutex.tryLock]. Held by exactly the same-id start/teardown paths — the pump's
     * claim-and-start (which is also how the addFiles DONE-restart's engine.start runs)
     * and [teardownEngine]'s re-check-and-cancel — so for one id a stale teardown
     * either cancels the dead torrent strictly before the fresh start, or re-checks
     * under the lock and skips.
     */
    private val idLocks = MutableStateFlow<Map<String, Mutex>>(emptyMap())

    private val activeStates =
        setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED)

    private val terminalStates =
        setOf(DownloadState.DONE, DownloadState.FAILED, DownloadState.CANCELLED)

    init {
        scope.launch {
            engine.events.collect { onEngineEvent(it) }
        }
        persistence?.let { store ->
            scope.launch {
                val entries = runCatching { store.load() }.getOrDefault(emptyList())
                val stagedRestored = mutableMapOf<String, StagedStart>()
                val requests = mutableListOf<DownloadRequest>()
                for (entry in entries) {
                    val bytes = entry.torrentBytesBase64
                        ?.let { b64 -> runCatching { Base64.decode(b64) }.getOrNull() }
                        ?: continue
                    stagedRestored[entry.id] =
                        StagedStart(bytes, entry.stagingPath, entry.wantedFiles.toSet())
                    requests += DownloadRequest(
                        id = entry.id,
                        topicId = entry.topicId,
                        title = entry.title,
                        forumName = entry.forumName,
                        sizeBytes = 0L, // selection-derived bookkeeping; Progress refreshes it
                    )
                }
                if (requests.isEmpty()) return@launch
                // staged first (same invariant as addFiles' DONE-restart): a pump racing
                // the item insert must never see a QUEUED item without its staged start.
                staged.update { it + stagedRestored }
                _items.update { list ->
                    // an enqueue may have claimed a restored id meanwhile — it wins
                    list + requests
                        .filter { req -> list.none { it.request.id == req.id } }
                        .map { DownloadItem(it) }
                }
                pump()
            }
        }
    }

    /**
     * Tears down the manager: cancels the engine-event collector, finalize continuations
     * and persistence writes. Call together with [TorrentEngine.close] on app exit.
     */
    fun close() {
        scope.cancel()
    }

    /**
     * Queues [request] for download. Idempotent per id: a duplicate enqueue while the
     * item is still in flight (QUEUED/DOWNLOADING/PAUSED/FINALIZING — e.g. the user
     * tapping Download again) is ignored; re-enqueueing a terminal item (DONE/FAILED/
     * CANCELLED) resets it to a fresh QUEUED item for a re-download. Two items with the
     * same id can never coexist — the UI lists items keyed by id and crashes on dupes.
     *
     * [wantedFileIndices] selects which torrent files to download (empty = all); the
     * engine applies skip priorities to the rest, so only selected files ever land in
     * staging.
     */
    fun enqueue(
        request: DownloadRequest,
        torrentBytes: ByteArray,
        stagingDir: String,
        wantedFileIndices: Set<Int> = emptySet(),
    ) {
        val existing = _items.value.firstOrNull { it.request.id == request.id }
        if (existing != null && existing.state !in terminalStates) return
        // Metadata-only parse (a few ms even for a discography); kept synchronous so the
        // staged files are always ready for the chooser by the time the item is visible.
        val files = runCatching { engine.parseFiles(torrentBytes) }.getOrDefault(emptyList())
        staged.update {
            it + (request.id to StagedStart(torrentBytes, stagingDir, wantedFileIndices, files))
        }
        var changed = false
        var reEnqueued = false
        _items.update { list ->
            // Re-check inside the atomic update: the pre-check above runs on a snapshot,
            // so two concurrent same-id enqueues could both pass it and both append.
            val current = list.firstOrNull { it.request.id == request.id }
            if (current != null && current.state !in terminalStates) return@update list
            changed = true
            if (current != null) {
                reEnqueued = true
                list.map { if (it.request.id == request.id) DownloadItem(request) else it }
            } else {
                list + DownloadItem(request)
            }
        }
        if (changed) {
            // the reset starts a new round for the id — bump before the pump's
            // engine.start so a pending DONE teardown of the previous round skips it
            if (reEnqueued) bumpGeneration(request.id)
            persistQueue()
        }
        pump()
    }

    /** Parsed file list of [id]'s staged torrent — the file choosers' data source. Empty
     *  when the torrent could not be parsed (no chooser) or the entry was dropped. */
    fun torrentFiles(id: String): List<TorrentFileInfo> = staged.value[id]?.files ?: emptyList()

    /** The wanted file set staged for [id] (empty set = every file, engine semantics). */
    fun wantedFiles(id: String): Set<Int> = staged.value[id]?.wantedFiles ?: emptySet()

    /**
     * Extends [id]'s selection with [newFileIndices] (the "add more folders" action; the
     * chooser locks out files already covered by the selection upstream).
     *
     * - DOWNLOADING/PAUSED: the engine re-points file priorities at the union — fetched
     *   pieces survive, so only the new files transfer. `staged` records the union so a
     *   later add sees the full selected set.
     * - DONE: the previous round's staging was deleted by finalize, and the finished
     *   engine torrent's piece state points at that deleted tree. The item restarts
     *   fresh (QUEUED) wanting only [newFileIndices] — the engine drops the old torrent
     *   and adds a new one — so albums already in the library are untouched and the new
     *   round finalizes into the same album folder.
     *
     * Returns false when there is nothing to do: unknown id, empty selection, every file
     * already wanted, or a state the action cannot extend (QUEUED/FINALIZING/FAILED/
     * CANCELLED). Note the DOWNLOADING branch races a completion happening between the
     * state read and the merge: an item that raced all the way to DONE no longer drops
     * the selection — the merge auto-transitions into the DONE restart with the union of
     * old+new wanted files. Racing only to FINALIZING (or FAILED/CANCELLED) still drops
     * the new files; repeating "add files" once the item settles recovers them.
     */
    fun addFiles(id: String, newFileIndices: Set<Int>): Boolean {
        if (newFileIndices.isEmpty()) return false
        val start = staged.value[id] ?: return false
        // Engine semantics: an empty wanted set selects every file, and an unparsable
        // torrent (files empty) downloaded everything too — in both cases nothing is left
        // to add.
        if (start.files.isEmpty()) return false
        val wanted = start.wantedFiles.ifEmpty { start.files.mapTo(mutableSetOf()) { it.index } }
        if (wanted.containsAll(start.files.map { it.index })) return false
        return when (_items.value.firstOrNull { it.request.id == id }?.state) {
            DownloadState.DOWNLOADING, DownloadState.PAUSED -> {
                val union = start.wantedFiles + newFileIndices
                var applied = false
                var sawDone = false
                _items.update { list ->
                    list.map { item ->
                        if (item.request.id != id) {
                            item
                        } else {
                            when (item.state) {
                                // a CAS retry re-runs this lambda on a fresh snapshot: a
                                // completion landing mid-merge shows up here as DONE —
                                // the DONE check must run on every retry so the
                                // raced-to-DONE fallback below can fire
                                DownloadState.DONE -> {
                                    sawDone = true
                                    item
                                }

                                DownloadState.DOWNLOADING, DownloadState.PAUSED -> {
                                    // the union sum is recomputed on every active-state
                                    // retry (idempotent): it keeps each CAS attempt's span
                                    // long, so under concurrent list mutations the merge
                                    // keeps losing the CAS ping-pong instead of committing
                                    // a snapshot taken before a mid-merge completion
                                    applied = true
                                    val unionBytes = start.files
                                        .filter { it.index in union }
                                        .sumOf { it.sizeBytes }
                                    item.copy(bytesTotal = if (unionBytes > 0) unionBytes else item.bytesTotal)
                                }

                                else -> item
                            }
                        }
                    }
                }
                // raced out of DOWNLOADING/PAUSED mid-merge and the item is now DONE: its
                // finalize already moved the old files, so instead of dropping the new
                // selection the merge degrades into the DONE restart with the union.
                if (sawDone) return restartDone(id, start, union)
                if (!applied) return false // raced to FINALIZING/FAILED/CANCELLED — drop
                staged.update { it + (id to start.copy(wantedFiles = union)) }
                engine.setWantedFiles(id, union)
                persistQueue()
                true
            }

            DownloadState.DONE -> restartDone(id, start, newFileIndices)

            else -> false
        }
    }

    /**
     * The DONE-restart path shared by [addFiles]' DONE branch and its raced-to-DONE
     * fallback: [wanted] becomes the fresh round's selection. Staged FIRST: flipping the
     * item to QUEUED before `staged` holds the new selection would let a concurrent pump
     * start the fresh round with the previous round's wanted-set.
     */
    private fun restartDone(id: String, start: StagedStart, wanted: Set<Int>): Boolean {
        staged.update { it + (id to start.copy(wantedFiles = wanted)) }
        var restarted = false
        _items.update { list ->
            list.map { item ->
                if (item.request.id == id && !restarted && item.state == DownloadState.DONE) {
                    restarted = true
                    val newBytes = start.files
                        .filter { it.index in wanted }
                        .sumOf { it.sizeBytes }
                        .takeIf { it > 0 }
                    DownloadItem(item.request.copy(sizeBytes = newBytes ?: item.request.sizeBytes))
                } else {
                    item
                }
            }
        }
        if (!restarted) {
            // raced away from DONE (remove / re-enqueue won): drop the staged
            // write unless a live item re-owns the id (its enqueue manages its
            // own staged entry).
            if (_items.value.none { it.request.id == id }) staged.update { it - id }
            return false
        }
        // the restart is a new round for the id — bump before the pump's
        // engine.start so the previous round's pending DONE teardown skips it;
        // that start serializes against the teardown on the id's mutex inside
        // the pump, so the teardown can never cancel the fresh round
        bumpGeneration(id)
        pump()
        persistQueue()
        return true
    }

    fun pause(id: String) {
        val paused = mutateIf(id, guard = { it.state == DownloadState.DOWNLOADING }) {
            it.copy(state = DownloadState.PAUSED)
        }
        if (paused) engine.pause(id)
    }

    fun resume(id: String) {
        val resumed = mutateIf(id, guard = { it.state == DownloadState.PAUSED }) {
            it.copy(state = DownloadState.DOWNLOADING)
        }
        if (resumed) {
            engine.resume(id)
            pump()
        }
    }

    /**
     * Cancels [id] (QUEUED/DOWNLOADING/PAUSED → CANCELLED). The state flip commits first
     * as a plain non-suspending CAS (lock hierarchy: the mutex is acquired only after the
     * state update commits, never inside an update lambda); the engine teardown then runs
     * under the id's start/teardown mutex via [cancelEngineUnderLock] — the same lock the
     * pump holds across its claim-and-[engine.start] span, so either order of
     * cancel-vs-start yields a correct end state:
     * - the CAS lands first: the pump's claim guard (QUEUED) then refuses the item — no
     *   start is ever issued for a CANCELLED id, and engine.cancel is a harmless no-op;
     * - the pump's claim+start lands first: the CAS still wins (both states are active)
     *   and the engine.cancel, serialized after the pump releases the mutex, tears the
     *   started torrent down. Without that serialization a cancel landing between the
     *   claim and engine.start would no-op against the not-yet-started torrent and leave
     *   it running behind a CANCELLED item.
     */
    fun cancel(id: String) {
        val cancelled = mutateIf(id, guard = { it.state in activeStates }) {
            it.copy(state = DownloadState.CANCELLED)
        }
        if (cancelled) {
            cancelEngineUnderLock(id)
            pump()
            persistQueue()
        }
    }

    fun pauseAll() {
        val paused = mutableSetOf<String>()
        _items.update { list ->
            list.map { item ->
                if (item.state == DownloadState.DOWNLOADING) {
                    paused += item.request.id
                    item.copy(state = DownloadState.PAUSED)
                } else {
                    item
                }
            }
        }
        paused.forEach { engine.pause(it) }
    }

    /**
     * History removal: drops [id] from the list and from `staged`. An active item is
     * first cancelled exactly like [cancel] does (state CANCELLED CAS + engine.cancel
     * under the id's mutex + pump — the mutex keeps the teardown serialized against a
     * pump claim-and-start mid-flight, see [cancelEngineUnderLock]) so the engine side
     * is torn down too; terminal items get the same engine teardown (their torrent
     * handle is dead weight — the fire-and-forget DONE path may not have run yet, and
     * re-firing cancel is harmless); one still FINALIZING — its finalize keeps running
     * but the entry is gone — is dropped directly. The list/staged drops are plain
     * non-suspending updates committed after the mutex section (lock hierarchy). A
     * deferred teardown re-checks under the lock and still fires for a dropped id
     * (nothing re-owns it); a re-enqueued round does re-own it and is left alone.
     * Unknown id is a no-op.
     */
    fun remove(id: String) {
        val current = _items.value.firstOrNull { it.request.id == id } ?: return
        if (current.state in activeStates) {
            val cancelled = mutateIf(id, guard = { it.state in activeStates }) {
                it.copy(state = DownloadState.CANCELLED)
            }
            if (cancelled) {
                cancelEngineUnderLock(id)
                pump()
            }
        } else if (current.state in terminalStates) {
            engine.cancel(id)
        }
        _items.update { list -> list.filterNot { it.request.id == id } }
        staged.update { it - id }
        persistQueue()
    }

    /**
     * History cleanup: drops every terminal item (DONE/FAILED/CANCELLED) and its
     * `staged` entry, tearing down each one's engine torrent; active items are untouched.
     */
    fun clearCompleted() {
        val terminal = _items.value
            .filter { it.state in terminalStates }
            .mapTo(mutableSetOf()) { it.request.id }
        if (terminal.isEmpty()) return
        terminal.forEach { engine.cancel(it) }
        _items.update { list -> list.filterNot { it.request.id in terminal } }
        staged.update { it - terminal }
        persistQueue()
    }

    /** Starts queued items while free slots remain; called after every state change. */
    private fun pump() {
        if (!pumpMutex.tryLock()) {
            // Another pump is running; it re-checks the queue each round, and this
            // backstop guarantees the fresh QUEUED item is reconsidered anyway.
            scope.launch { runPump() }
            return
        }
        try {
            runPump()
        } finally {
            pumpMutex.unlock()
        }
    }

    private fun runPump() {
        // Queued items whose staged start is gone (staging dropped under a live item)
        // are skipped, not stallers — the pump keeps serving the rest of the queue.
        val skipped = mutableSetOf<String>()
        while (true) {
            val list = _items.value
            // re-read per iteration so a live Settings change applies mid-drain; the
            // stores clamp on write, this clamps at read against hand-edited values.
            val slots = maxConcurrent.value.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)
            val free = slots - list.count {
                it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
            }
            if (free <= 0) return
            val next =
                list.firstOrNull { it.state == DownloadState.QUEUED && it.request.id !in skipped }
                    ?: return
            val start = staged.value[next.request.id]
            if (start == null) {
                skipped += next.request.id
                continue
            }
            // claim-and-start under the id's mutex: a fire-and-forget DONE teardown for
            // this id either cancels the previous round's dead torrent strictly before
            // this start, or re-checks under the lock and skips — a stale cancel can no
            // longer land after (and kill) this fresh start. A busy lock means such a
            // teardown (or another start of the id) is mid-flight; retry via the pump
            // backstop instead of suspending (the pump stays non-suspending).
            val idLock = lockFor(next.request.id)
            if (!idLock.tryLock()) {
                scope.launch { runPump() }
                return
            }
            try {
                val claimed =
                    mutateIf(next.request.id, guard = { it.state == DownloadState.QUEUED }) {
                        it.copy(state = DownloadState.DOWNLOADING)
                    }
                if (claimed) {
                    // start() only schedules work on the engine's own IO scope (no caller-thread
                    // blocking), and a synchronous throw (fake/real engine rejecting the args)
                    // becomes a FAILED item instead of escaping into the enqueue caller.
                    try {
                        engine.start(next.request.id, start.torrentBytes, start.stagingDir, start.wantedFiles)
                    } catch (t: Throwable) {
                        onEngineEvent(EngineEvent.Failed(next.request.id, t.message ?: "engine start failed"))
                    }
                }
            } finally {
                idLock.unlock()
            }
        }
    }

    /**
     * Fires [engine.cancel] for [id] under the id's start/teardown mutex — the
     * serialization that closes the cancel-vs-pump-claim window (see [cancel]): the pump
     * holds the same mutex across its claim-and-start, so an engine.cancel issued here
     * either precedes that start entirely (the claim guard then refuses the CANCELLED
     * item) or is ordered strictly after the started torrent exists and kills it.
     * Non-suspending fast path via [Mutex.tryLock]; contended (a start or teardown is
     * mid-flight — holders never suspend for long) the cancel defers to [scope] exactly
     * like the pump backstop does. After acquiring, re-check the state and only fire
     * while the id is gone (its drop already owns the teardown) or still CANCELLED — any
     * other state means a re-enqueued round re-took the id and owns the engine side, so
     * the deferred cancel must not kill its fresh torrent.
     */
    private fun cancelEngineUnderLock(id: String) {
        val idLock = lockFor(id)
        if (idLock.tryLock()) {
            try {
                cancelIfStillOwned(id)
            } finally {
                idLock.unlock()
            }
        } else {
            scope.launch {
                idLock.withLock { cancelIfStillOwned(id) }
            }
        }
    }

    /** The deferred/sync cancel's re-check: see [cancelEngineUnderLock]. */
    private fun cancelIfStillOwned(id: String) {
        val item = _items.value.firstOrNull { it.request.id == id }
        if (item == null || item.state == DownloadState.CANCELLED) engine.cancel(id)
    }

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Added -> Unit
            is EngineEvent.Progress ->
                mutateIf(event.id, guard = { it.state == DownloadState.DOWNLOADING }) {
                    it.copy(
                        bytesDone = event.bytesDone,
                        bytesTotal = if (event.bytesTotal > 0) event.bytesTotal else it.bytesTotal,
                        downloadSpeed = event.downloadSpeed,
                        peers = event.peers,
                    )
                }

            is EngineEvent.Completed -> {
                val entered =
                    mutateIf(event.id, guard = { it.state == DownloadState.DOWNLOADING }) {
                        it.copy(state = DownloadState.FINALIZING)
                    }
                if (entered) {
                    pump() // FINALIZING released the slot; queued items may start while finalize runs
                    val id = event.id
                    scope.launch {
                        val snapshot =
                            _items.value.firstOrNull { it.request.id == id } ?: return@launch
                        val stagingDir = staged.value[id]?.stagingDir ?: ""
                        val result = finalize(snapshot, stagingDir)
                        when {
                            result.isSuccess -> {
                                // Capture the generation while the item is FINALIZING: no
                                // bump path (enqueue reset / addFiles restart) can run
                                // against a non-terminal id, so this is unambiguously the
                                // generation at DONE time — a round taking the id over
                                // after the flip must not be cancelled by the teardown.
                                val generation = generations.value[id]
                                val done =
                                    mutateIf(id, guard = { it.state == DownloadState.FINALIZING }) {
                                        it.copy(state = DownloadState.DONE, outputNote = result.getOrNull())
                                    }
                                if (done) {
                                    scope.launch { teardownEngine(id, generation) }
                                    persistQueue()
                                }
                            }

                            else -> {
                                val failed =
                                    mutateIf(id, guard = { it.state == DownloadState.FINALIZING }) {
                                        it.copy(
                                            state = DownloadState.FAILED,
                                            error = result.exceptionOrNull()?.message,
                                        )
                                    }
                                if (failed) persistQueue()
                            }
                        }
                        pump()
                    }
                }
            }

            is EngineEvent.Failed -> {
                val failed = mutateIf(event.id, guard = { it.state in activeStates }) {
                    it.copy(state = DownloadState.FAILED, error = event.message)
                }
                if (failed) {
                    pump()
                    persistQueue()
                }
            }
        }
    }

    /**
     * Fire-and-forget engine teardown of a terminal item's torrent — the handle is dead
     * weight (piece cache, announce timer) once the payload is finalized or abandoned.
     * The id may have been re-taken meanwhile (retry enqueue / addFiles' DONE-restart);
     * both bump [generations]. Under the id's mutex — the same lock every engine.start
     * for the id goes through — cancel only while the item is still terminal AND
     * [generation] (captured at DONE time) is unchanged: a mismatch means a fresh round
     * owns the id. The lock closes the re-check-then-cancel window completely: a start
     * cannot commit between the re-check and the cancel, so a restarted round's fresh
     * engine.start is never killed by a stale teardown.
     */
    private suspend fun teardownEngine(id: String, generation: Int?) {
        lockFor(id).withLock {
            val item = _items.value.firstOrNull { it.request.id == id } ?: return@withLock
            if (item.state in terminalStates && generations.value[id] == generation) engine.cancel(id)
        }
    }

    /** Bumps [id]'s generation — a new round took over the id (see [generations]). */
    private fun bumpGeneration(id: String) {
        generations.update { it + (id to ((it[id] ?: 0) + 1)) }
    }

    /** [id]'s start/teardown lock (see [idLocks]); concurrent callers share one instance. */
    private fun lockFor(id: String): Mutex {
        var lock = Mutex()
        idLocks.update { map ->
            val existing = map[id]
            if (existing == null) map + (id to lock) else {
                lock = existing
                map
            }
        }
        return lock
    }

    /** Non-terminal items with their staged start; terminal items need no resume. */
    private fun snapshotForStore(): List<PersistedDownload> {
        val stagedNow = staged.value
        return _items.value.mapNotNull { item ->
            if (item.state in terminalStates) return@mapNotNull null
            val start = stagedNow[item.request.id] ?: return@mapNotNull null
            PersistedDownload(
                id = item.request.id,
                topicId = item.request.topicId,
                title = item.request.title,
                forumName = item.request.forumName,
                torrentBytesBase64 = Base64.encode(start.torrentBytes),
                stagingPath = start.stagingDir,
                wantedFiles = start.wantedFiles.sorted(),
            )
        }
    }

    /** Fire-and-forget store flush, serialized by [persistenceMutex]; failures swallowed. */
    private fun persistQueue() {
        val store = persistence ?: return
        scope.launch {
            persistenceMutex.withLock {
                runCatching { store.save(snapshotForStore()) }
            }
        }
    }

    /** Applies [transform] to the item with [id] when [guard] holds; returns whether it applied. */
    private fun mutateIf(
        id: String,
        guard: (DownloadItem) -> Boolean,
        transform: (DownloadItem) -> DownloadItem,
    ): Boolean {
        var applied = false
        _items.update { list ->
            list.map { item ->
                if (item.request.id == id && guard(item)) {
                    applied = true
                    transform(item)
                } else {
                    item
                }
            }
        }
        return applied
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT = 3
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 5
    }
}
