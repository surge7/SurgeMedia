package com.surgemusic.core.downloads

import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
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

    private data class StagedStart(val torrentBytes: ByteArray, val stagingDir: String)

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
     */
    fun enqueue(request: DownloadRequest, torrentBytes: ByteArray, stagingDir: String) {
        val existing = _items.value.firstOrNull { it.request.id == request.id }
        if (existing != null && existing.state !in terminalStates) return
        staged.update { it + (request.id to StagedStart(torrentBytes, stagingDir)) }
        _items.update { list ->
            if (existing != null) {
                list.map { if (it.request.id == request.id) DownloadItem(request) else it }
            } else {
                list + DownloadItem(request)
            }
        }
        pump()
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
                    engine.start(next.request.id, start.torrentBytes, start.stagingDir)
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
