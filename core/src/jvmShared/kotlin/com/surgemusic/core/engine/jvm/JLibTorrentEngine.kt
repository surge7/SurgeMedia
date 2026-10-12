package com.surgemusic.core.engine.jvm

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
import com.frostwire.jlibtorrent.swig.address
import com.frostwire.jlibtorrent.swig.error_code
import com.frostwire.jlibtorrent.swig.libtorrent
import com.frostwire.jlibtorrent.swig.tcp_endpoint
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.EngineSnapshot
import com.surgemusic.core.engine.EngineState
import com.surgemusic.core.engine.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

class JLibTorrentEngine : TorrentEngine {

    private val session = SessionManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private data class Active(val info: TorrentInfo, @Volatile var handle: TorrentHandle?)
    private val active = ConcurrentHashMap<String, Active>()
    private val completed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        session.start()
        scope.launch {
            while (isActive) {
                active.forEach { (id, _) -> sampleStatus(id) }
                delay(1_000)
            }
        }
    }

    /**
     * Emits one [EngineEvent] for `id` from its current status: Progress, or Completed
     * exactly once (the atomic `completed.add(id)` check-and-add guards double emission
     * even when the poll loop and the initial sample race).
     */
    private fun sampleStatus(id: String) {
        val h = active[id]?.handle ?: return
        if (id in completed) return
        runCatching {
            val st = h.status()
            if (isFinished(st)) {
                if (completed.add(id)) _events.tryEmit(EngineEvent.Completed(id))
            } else {
                _events.tryEmit(
                    EngineEvent.Progress(
                        id = id,
                        bytesDone = st.totalWantedDone(),
                        bytesTotal = st.totalWanted(),
                        downloadSpeed = st.downloadPayloadRate().toLong(),
                        peers = st.numPeers(),
                    ),
                )
            }
        }
    }

    // jlibtorrent 2.0.12.9: no SessionManager.listenPort(); the swig session_handle
    // exposes listen_port().
    override fun listenPort(): Int = session.swig().listen_port()

    override fun start(id: String, torrentBytes: ByteArray, targetDir: String) =
        start(id, torrentBytes, targetDir, extraPeers = emptyList())

    /**
     * Overload (the [TorrentEngine] interface fixes the 3-arg start, and Kotlin overrides
     * cannot grow parameters) for callers that must force-connect peers, e.g. the
     * loopback test harness.
     */
    fun start(
        id: String,
        torrentBytes: ByteArray,
        targetDir: String,
        extraPeers: List<Pair<String, Int>>,
    ) {
        val info = TorrentInfo(torrentBytes)
        val dir = File(targetDir).apply { mkdirs() }
        active[id] = Active(info, null)
        session.download(info, dir)
        scope.launch { // wait for the handle to appear
            val deadline = System.currentTimeMillis() + 20_000
            while (active[id]?.handle == null && System.currentTimeMillis() < deadline) {
                // jlibtorrent 2.0.12.9 has no TorrentInfo.infoHash(); the test torrents
                // are v1, so the v1 info-hash is the lookup key (Phase-1 usage).
                val h = session.find(info.infoHashV1())
                if (h != null) {
                    active[id]?.handle = h
                    _events.tryEmit(EngineEvent.Added(id))
                    extraPeers.forEach { (ip, port) -> addPeer(h, ip, port) }
                    // First status sample immediately (mirrors the Phase-1 flow's first
                    // Progress): a torrent that finishes entirely between 1s poll ticks
                    // would otherwise emit no Progress at all.
                    sampleStatus(id)
                    break
                }
                delay(200)
            }
            if (active[id]?.handle == null) {
                active.remove(id)
                _events.tryEmit(EngineEvent.Failed(id, "torrent handle not added within timeout"))
            }
        }
    }

    override fun pause(id: String) {
        active[id]?.handle?.let { runCatching { it.pause() } }
    }

    override fun resume(id: String) {
        active[id]?.handle?.let { runCatching { it.resume() } }
    }

    override fun cancel(id: String) {
        active.remove(id)?.handle?.let { runCatching { session.remove(it) } }
    }

    override fun snapshot(id: String): EngineSnapshot? {
        val h = active[id]?.handle ?: return null
        return runCatching {
            val st = h.status()
            EngineSnapshot(
                id = id,
                state = when {
                    isFinished(st) -> EngineState.DONE
                    isPaused(st) -> EngineState.PAUSED
                    else -> EngineState.DOWNLOADING
                },
                bytesDone = st.totalWantedDone(),
                bytesTotal = st.totalWanted(),
                downloadSpeed = st.downloadPayloadRate().toLong(),
                peers = st.numPeers(),
            )
        }.getOrNull()
    }

    override fun close() {
        scope.cancel()
        runCatching { session.stop() }
    }

    // jlibtorrent 2.0.12.9: TorrentStatus has no isPaused(); the paused state is a bit
    // in the swig flags object, tested via torrent_flags_t.and_(paused).nonZero().
    private fun isPaused(st: TorrentStatus): Boolean =
        runCatching { st.flags().and_(libtorrent.getPaused()).nonZero() }.getOrDefault(false)

    // jlibtorrent 2.0.12.9: the flags-object spelling `flags().is_finished()` does not
    // exist on `torrent_flags_t`; the wrapper `TorrentStatus.isFinished()` does. The
    // progress() >= 1.0f fallback covers rare float-rounding cases at 100%.
    private fun isFinished(st: TorrentStatus): Boolean =
        runCatching { st.isFinished() }.getOrDefault(false) || st.progress() >= 1.0f

    /**
     * jlibtorrent 2.0.12.9 has no `TorrentHandle.addPeer(String, Int)` wrapper;
     * the equivalent is the swig-level `torrent_handle.connect_peer(tcp_endpoint)`.
     */
    private fun addPeer(handle: TorrentHandle, ip: String, port: Int) {
        val ec = error_code()
        val addr = address.from_string(ip, ec)
        if (ec.value() == 0) handle.swig().connect_peer(tcp_endpoint(addr, port))
    }
}
