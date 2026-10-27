package com.surgemusic.core.engine.jvm

import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.swig.address
import com.frostwire.jlibtorrent.swig.add_torrent_params
import com.frostwire.jlibtorrent.swig.byte_vector
import com.frostwire.jlibtorrent.swig.error_code
import com.frostwire.jlibtorrent.swig.libtorrent
import com.frostwire.jlibtorrent.swig.tcp_endpoint
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.EngineSnapshot
import com.surgemusic.core.engine.EngineState
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.TorrentFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private companion object {
        /** Tracker-shim re-announce cadence (trackers default to 30–60 min; be eager). */
        const val ANNOUNCE_INTERVAL_MS = 180_000L

        /**
         * How long [sampleStatus] holds back a finished status after a [setWantedFiles]
         * — long enough for the session thread to apply the new priorities (observed
         * applied within well under a second; this is the safety margin).
         */
        const val PRIORITY_SETTLE_MS = 5_000L
    }

    private val session = SessionManager()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private data class Active(val info: TorrentInfo, @Volatile var handle: TorrentHandle?)
    private val active = ConcurrentHashMap<String, Active>()
    private val completed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** One announce loop per id — a new [announceTrackers] call retires the previous job. */
    private val announceJobs = ConcurrentHashMap<String, Job>()

    /** [setWantedFiles] timestamp per id — arms the stale-finished guard in [sampleStatus]. */
    private val pendingWanted = ConcurrentHashMap<String, Long>()

    init {
        session.start()
        // Observability: libtorrent failure alerts (web-seed/tracker/peer/file errors)
        // previously vanished silently — a torrent stuck at 0% had no diagnosable trace.
        // stdout lands in logcat under System.out for both Android and desktop runs.
        session.addListener(object : AlertListener {
            override fun types(): IntArray = intArrayOf(
                AlertType.TORRENT_ERROR.swig(),
                AlertType.URL_SEED.swig(),
                AlertType.TRACKER_ERROR.swig(),
                AlertType.TRACKER_WARNING.swig(),
                AlertType.PEER_ERROR.swig(),
                AlertType.FILE_ERROR.swig(),
                AlertType.LISTEN_FAILED.swig(),
                AlertType.DHT_ERROR.swig(),
                AlertType.SESSION_ERROR.swig(),
                AlertType.STORAGE_MOVED_FAILED.swig(),
            )

            override fun alert(alert: Alert<*>) {
                println("JLibTorrentEngine alert: ${alert.message()}")
            }
        })
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
     *
     * [pendingWanted] guards the window right after a [setWantedFiles]: the priority
     * change rides the session thread asynchronously, so a finished status read in that
     * window may predate the change (observed: priorities read back applied while
     * totalWanted still showed the old selection) — emitting Completed there would
     * finalize away a staging tree the new files are still downloading into. A finished
     * sample inside the settle window is held back; the first non-finished sample (the
     * change took effect) or the window's expiry (the new files were already complete —
     * piece spillover) re-enables Completed.
     */
    private fun sampleStatus(id: String) {
        val h = active[id]?.handle ?: return
        val settledAt = pendingWanted[id]
        runCatching {
            val st = h.status()
            if (settledAt != null) {
                if (!isFinished(st) || System.currentTimeMillis() - settledAt >= PRIORITY_SETTLE_MS) {
                    pendingWanted.remove(id)
                } else {
                    return
                }
            }
            if (id in completed) return
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

    /**
     * Tracker announce shim (see [TrackerAnnouncer]): under Android per-app VPNs
     * libtorrent's own announces never go out (native DNS/route enumeration fail on
     * tun0), so announce via Ktor/OkHttp and connect the returned peers directly.
     * Re-announces every [ANNOUNCE_INTERVAL_MS] while the torrent is active and
     * unfinished; the loop exits on cancel/remove/completion (active[id] gone).
     */
    private fun announceTrackers(id: String, info: TorrentInfo, handle: TorrentHandle) {
        val urls = runCatching { info.trackers().map { it.url() } }.getOrDefault(emptyList())
            .filter { it.startsWith("http") }
        if (urls.isEmpty()) return
        // Retire the previous loop for this id first — a DONE torrent extended with more
        // files restarts announcing, and the old loop (still inside its 3-min delay) would
        // otherwise resume alongside the new one and double every announce.
        announceJobs.remove(id)?.cancel()
        announceJobs[id] = scope.launch {
            val announcer = TrackerAnnouncer()
            val infoHash = info.infoHashV1().toString()
            val total = runCatching { info.totalSize() }.getOrDefault(0L)
            var started = true
            while (active[id]?.handle != null && id !in completed) {
                urls.forEach { url ->
                    val peers = announcer.announceOnce(
                        announceUrl = url,
                        infoHashHex = infoHash,
                        listenPort = listenPort(),
                        leftBytes = total,
                        started = started,
                    )
                    if (peers != null) {
                        println("JLibTorrentEngine tracker-shim: $url → ${peers.size} peer(s)")
                        peers.forEach { (ip, port) -> addPeer(handle, ip, port) }
                    }
                }
                started = false
                delay(ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    // jlibtorrent 2.0.12.9: no SessionManager.listenPort(); the swig session_handle
    // exposes listen_port().
    override fun listenPort(): Int = session.swig().listen_port()

    /**
     * Parses the torrent's file list (pad files excluded — they are padding, not content,
     * and only clutter a chooser UI). Throws on invalid torrent bytes; callers surface
     * that as a download failure.
     */
    override fun parseFiles(torrentBytes: ByteArray): List<TorrentFileInfo> {
        val files = TorrentInfo(torrentBytes).files()
        return (0 until files.numFiles()).mapNotNull { i ->
            if (files.padFileAt(i)) return@mapNotNull null
            TorrentFileInfo(index = i, path = files.filePath(i), sizeBytes = files.fileSize(i))
        }
    }

    override fun start(id: String, torrentBytes: ByteArray, targetDir: String, wantedFiles: Set<Int>) =
        start(id, torrentBytes, targetDir, wantedFiles, extraPeers = emptyList())

    /**
     * Full overload (the [TorrentEngine] interface fixes the 4-arg start, and Kotlin
     * overrides cannot grow parameters) for callers that must force-connect peers, e.g. the
     * loopback test harness.
     */
    fun start(
        id: String,
        torrentBytes: ByteArray,
        targetDir: String,
        wantedFiles: Set<Int>,
        extraPeers: List<Pair<String, Int>>,
    ) {
        // Parse + add on the engine's IO scope: callers may be on any thread (incl.
        // Android's Main), and TorrentInfo parsing / directory setup must neither block
        // nor throw through them. Failures surface as EngineEvent.Failed for the id.
        scope.launch {
            val info = try {
                TorrentInfo(torrentBytes)
            } catch (t: Throwable) {
                _events.tryEmit(
                    EngineEvent.Failed(id, "invalid torrent: ${t.message ?: t::class.simpleName}"),
                )
                return@launch
            }
            val dir = try {
                File(targetDir).apply { mkdirs() }
            } catch (t: Throwable) {
                _events.tryEmit(
                    EngineEvent.Failed(id, "staging dir unusable: ${t.message ?: t::class.simpleName}"),
                )
                return@launch
            }
            // Restart, not reuse: a torrent already present for this id (or its info-hash)
            // belongs to a previous round whose lifecycle is over — a DONE item extended
            // with more files (its staging was finalized away, so the old piece state is
            // fiction) or a FAILED/CANCELLED retry. Re-adding the same info-hash while
            // the old torrent lingers is rejected as a duplicate, and reviving a finished
            // torrent via live priorities races libtorrent's async session thread (the
            // stale finished status would emit a bogus Completed). So: remove the old
            // torrent, wait for the session to actually drop it, then add fresh — the
            // fresh add downloads only the newly wanted files.
            announceJobs.remove(id)?.cancel()
            completed.remove(id)
            pendingWanted.remove(id)
            active.remove(id)?.handle?.let { runCatching { session.remove(it) } }
            val stray = runCatching { session.find(info.infoHashV1()) }.getOrNull()
            if (stray != null) {
                runCatching { session.remove(stray) }
                val goneDeadline = System.currentTimeMillis() + 10_000
                while (
                    runCatching { session.find(info.infoHashV1()) }.getOrNull() != null &&
                    System.currentTimeMillis() < goneDeadline
                ) {
                    delay(200)
                }
                if (runCatching { session.find(info.infoHashV1()) }.getOrNull() != null) {
                    _events.tryEmit(
                        EngineEvent.Failed(id, "previous torrent for this id still in session"),
                    )
                    return@launch
                }
            }
            active[id] = Active(info, null)
            // Selective download: skip priorities ride the add itself, so no piece of an
            // unwanted file is ever requested. Done via raw add_torrent_params because
            // SessionManager's 6-arg download() only applies its priorities array when
            // the torrent is ALREADY in the session (it silently drops it for fresh adds)
            // — mirroring that method's own ti/savePath/flags handling here.
            val priorities: Array<Priority>? = if (wantedFiles.isEmpty()) {
                null
            } else {
                val n = info.files().numFiles()
                Priority.array(Priority.NORMAL, n).also { p ->
                    for (i in 0 until n) if (i !in wantedFiles) p[i] = Priority.IGNORE
                }
            }
            try {
                val params = add_torrent_params.create_instance()
                params.set_ti(info.swig())
                params.setSave_path(dir.absolutePath)
                params.setFlags(params.getFlags().or_(libtorrent.getDefault_flags()))
                if (priorities != null) {
                    // set_file_priorities2 is the real atp.file_priorities setter (what
                    // AddTorrentParams.filePriorities uses); Priority.array2byte_vector
                    // is package-private, so fill the vector inline (swig ints as bytes).
                    val vec = byte_vector()
                    priorities.forEach { vec.add(it.swig().toByte()) }
                    params.set_file_priorities2(vec)
                }
                session.swig().async_add_torrent(params)
            } catch (t: Throwable) {
                active.remove(id)
                _events.tryEmit(
                    EngineEvent.Failed(id, "session rejected torrent: ${t.message ?: t::class.simpleName}"),
                )
                return@launch
            }
            val deadline = System.currentTimeMillis() + 20_000
            while (active[id]?.handle == null && System.currentTimeMillis() < deadline) {
                // jlibtorrent 2.0.12.9 has no TorrentInfo.infoHash(); the test torrents
                // are v1, so the v1 info-hash is the lookup key (Phase-1 usage).
                val h = session.find(info.infoHashV1())
                if (h != null) {
                    active[id]?.handle = h
                    _events.tryEmit(EngineEvent.Added(id))
                    extraPeers.forEach { (ip, port) -> addPeer(h, ip, port) }
                    announceTrackers(id, info, h)
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

    /**
     * Live selection change (DownloadManager.addFiles on a DOWNLOADING/PAUSED item):
     * re-points file priorities at [wantedFiles] and resumes the torrent — unless the
     * user paused it, in which case it stays paused and the new files download on the
     * next resume. The id leaves `completed` so a fresh exactly-once Completed can fire
     * for the enlarged selection; [pendingWanted] arms the stale-finished guard in
     * [sampleStatus] (a priority change rides the session thread asynchronously, and a
     * finished status read right after one can predate the change).
     */
    override fun setWantedFiles(id: String, wantedFiles: Set<Int>) {
        val a = active[id] ?: return
        val handle = a.handle ?: return
        scope.launch {
            completed.remove(id)
            applyPriorities(handle, a.info, wantedFiles)
            val paused = runCatching { isPaused(handle.status()) }.getOrDefault(false)
            if (!paused) runCatching { handle.resume() }
            pendingWanted[id] = System.currentTimeMillis()
        }
    }

    /**
     * Points [handle]'s per-file priorities at [wantedFiles]: wanted → NORMAL, the rest
     * → IGNORE (empty set = every file). Per-file calls, not the bulk
     * `prioritizeFiles()` — the bulk setter's effect was never observable here while the
     * per-file one verifiably stuck. libtorrent keeps pieces it already has, so
     * re-pointing at a half-downloaded torrent preserves its progress.
     */
    private fun applyPriorities(handle: TorrentHandle, info: TorrentInfo, wantedFiles: Set<Int>) {
        runCatching {
            val n = info.files().numFiles()
            for (i in 0 until n) {
                handle.filePriority(
                    i,
                    if (wantedFiles.isEmpty() || i in wantedFiles) Priority.NORMAL else Priority.IGNORE,
                )
            }
        }
    }

    override fun cancel(id: String) {
        announceJobs.remove(id)?.cancel()
        pendingWanted.remove(id)
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
