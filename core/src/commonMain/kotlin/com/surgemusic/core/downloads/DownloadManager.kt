package com.surgemusic.core.downloads

import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.TorrentFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
 * In-memory download queue and state machine over [TorrentEngine]
 * (spec §7 persistence is a hardening-phase item; no database in this phase).
 *
 * Slot accounting: an item holds a concurrency slot while DOWNLOADING or PAUSED
 * (pause does not free the slot; cancel/failure do). FINALIZING runs off the
 * concurrency count — the slot is freed when the engine reports Completed.
 */
class DownloadManager(
    private val engine: TorrentEngine,
    private val maxConcurrent: Int = 3,
    private val finalize: suspend (item: DownloadItem, stagingDir: String) -> Result<String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pumpMutex = Mutex()

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

    private val activeStates =
        setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED)

    private val terminalStates =
        setOf(DownloadState.DONE, DownloadState.FAILED, DownloadState.CANCELLED)

    init {
        scope.launch {
            engine.events.collect { onEngineEvent(it) }
        }
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
        _items.update { list ->
            if (existing != null) {
                list.map { if (it.request.id == request.id) DownloadItem(request) else it }
            } else {
                list + DownloadItem(request)
            }
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
     * state read and the engine call — if the torrent completes first, its finalize moved
     * the old files and the new ones stay unstaged; repeating "add files" (the DONE path)
     * recovers them.
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
        var liveUnion: Set<Int>? = null
        var restarted = false
        _items.update { list ->
            list.map { item ->
                if (item.request.id != id || liveUnion != null || restarted) return@map item
                when (item.state) {
                    DownloadState.DOWNLOADING, DownloadState.PAUSED -> {
                        val union = start.wantedFiles + newFileIndices
                        liveUnion = union
                        val unionBytes = start.files
                            .filter { it.index in union }
                            .sumOf { it.sizeBytes }
                        item.copy(bytesTotal = if (unionBytes > 0) unionBytes else item.bytesTotal)
                    }

                    DownloadState.DONE -> {
                        restarted = true
                        val newBytes = start.files
                            .filter { it.index in newFileIndices }
                            .sumOf { it.sizeBytes }
                            .takeIf { it > 0 }
                        DownloadItem(item.request.copy(sizeBytes = newBytes ?: item.request.sizeBytes))
                    }

                    else -> item
                }
            }
        }
        return when {
            liveUnion != null -> {
                val union = liveUnion!!
                staged.update { it + (id to start.copy(wantedFiles = union)) }
                engine.setWantedFiles(id, union)
                true
            }

            restarted -> {
                staged.update { it + (id to start.copy(wantedFiles = newFileIndices)) }
                pump()
                true
            }

            else -> false
        }
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

    fun cancel(id: String) {
        val cancelled = mutateIf(id, guard = { it.state in activeStates }) {
            it.copy(state = DownloadState.CANCELLED)
        }
        if (cancelled) {
            engine.cancel(id)
            pump()
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
     * first cancelled exactly like [cancel] does (state CANCELLED + engine.cancel +
     * pump) so the engine side is torn down too; terminal items (and one still
     * FINALIZING — its finalize keeps running but the entry is gone) are dropped
     * directly. Unknown id is a no-op.
     */
    fun remove(id: String) {
        val current = _items.value.firstOrNull { it.request.id == id } ?: return
        if (current.state in activeStates) {
            val cancelled = mutateIf(id, guard = { it.state in activeStates }) {
                it.copy(state = DownloadState.CANCELLED)
            }
            if (cancelled) {
                engine.cancel(id)
                pump()
            }
        }
        _items.update { list -> list.filterNot { it.request.id == id } }
        staged.update { it - id }
    }

    /**
     * History cleanup: drops every terminal item (DONE/FAILED/CANCELLED) and its
     * `staged` entry; active items are untouched.
     */
    fun clearCompleted() {
        val terminal = _items.value
            .filter { it.state in terminalStates }
            .mapTo(mutableSetOf()) { it.request.id }
        if (terminal.isEmpty()) return
        _items.update { list -> list.filterNot { it.request.id in terminal } }
        staged.update { it - terminal }
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
        while (true) {
            val list = _items.value
            val free = maxConcurrent - list.count {
                it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
            }
            if (free <= 0) return
            val next = list.firstOrNull { it.state == DownloadState.QUEUED } ?: return
            val start = staged.value[next.request.id] ?: return
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
        }
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
                            result.isSuccess ->
                                mutateIf(id, guard = { it.state == DownloadState.FINALIZING }) {
                                    it.copy(state = DownloadState.DONE, outputNote = result.getOrNull())
                                }

                            else ->
                                mutateIf(id, guard = { it.state == DownloadState.FINALIZING }) {
                                    it.copy(
                                        state = DownloadState.FAILED,
                                        error = result.exceptionOrNull()?.message,
                                    )
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
                if (failed) pump()
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
}
