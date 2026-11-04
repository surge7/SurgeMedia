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
import com.surgemusic.core.engine.TorrentEngine
import com.surgemusic.core.engine.TorrentFileInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

class JLibTorrentEngine(
    /** false disables the DHT bootstrap (loopback tests must stay off-router). */
    private val dhtEnabled: Boolean = true,
) : TorrentEngine {

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

    /**
     * Torrent entry, from [start] until adoption (handle == null — the "add window") or
     * removal. Calls landing in the add window (cancel/pause/resume/[setWantedFiles])
     * latch their intent here; the find loop in [start] applies the latches when it
     * adopts the handle.
     */
    private data class Active(
        val info: TorrentInfo,
        @Volatile var handle: TorrentHandle?,
        @Volatile var wantPaused: Boolean = false,
        @Volatile var wantCancelled: Boolean = false,
        @Volatile var pendingWanted: Set<Int>? = null,
    )

    private val active = ConcurrentHashMap<String, Active>()

    /**
     * Serializes {cancel's wantCancelled latch + session sweep} against the find loop's
     * {wantCancelled re-check + handle adoption}: both check-then-act sequences hold it
     * across their whole critical section, so a cancel racing the add window can no
     * longer slip between the loop's latch check and the handle write (that window left
     * the adopted torrent downloading and emitting events for a cancelled id).
     */
    private val adoptionLock = Any()

    private val completed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Errored ids — guards the exactly-once [EngineEvent.Failed] emission in [sampleStatus]. */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** One announce loop per id — a new [announceTrackers] call retires the previous job. */
    private val announceJobs = ConcurrentHashMap<String, Job>()

    /** [setWantedFiles] timestamp per id — arms the stale-finished guard in [sampleStatus]. */
    private val pendingWanted = ConcurrentHashMap<String, Long>()

    /**
     * Tracker-shim HTTP client — ONE per engine (created lazily on the first announce;
     * TrackerAnnouncer instances come and go per announce loop but the client outlives
     * them), closed in [close]. Per-instance clients leaked an OkHttp dispatcher
     * (threads + pool) on every engine start().
     */
    private val announceHttp: Lazy<HttpClient> = lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        }
    }

    init {
        session.start()
        if (!dhtEnabled) {
            // jlibtorrent 2.0.12.9: SessionManager.stopDht() is toggleDht(false) —
            // applySettings(SettingsPack().setEnableDht(false)); no-op when already off.
            runCatching { session.stopDht() }
        }
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
     * A torrent-level error (libtorrent's torrent_status.error — `st.errorCode().value()
     * != 0` in jlibtorrent 2.0.12.9) emits [EngineEvent.Failed] exactly once per id
     * (guarded by the `failed` set, same pattern as `completed`) and stops Progress —
     * an errored torrent never moves, so polling it would spam identical Progress.
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
            val ec = st.errorCode()
            if (ec.value() != 0) {
                if (failed.add(id)) _events.tryEmit(EngineEvent.Failed(id, "torrent error ${ec.value()}: ${ec.message()}"))
                return
            }
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
            val announcer = TrackerAnnouncer(announceHttp.value)
            val infoHash = info.infoHashV1().toString()
            val total = runCatching { info.totalSize() }.getOrDefault(0L)
            var started = true
            while (active[id]?.handle != null && id !in completed) {
                // left= counts the WANTED bytes (selection-aware: status().totalWanted()),
                // not the whole torrent — left>0 is what marks us a leech; claiming the
                // full size made selective announces lie to the tracker. totalSize() is
                // the fallback for a failed/empty status read.
                val left = runCatching { handle.status().totalWanted() }.getOrNull()
                    ?.takeIf { it > 0 } ?: total
                urls.forEach { url ->
                    val peers = announcer.announceOnce(
                        announceUrl = url,
                        infoHashHex = infoHash,
                        listenPort = listenPort(),
                        leftBytes = left,
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
                File(targetDir).apply { mkdirs() }.also {
                    // mkdirs() returns false without throwing (existing file, permissions);
                    // letting it slide sent libtorrent into an eternal 0% stall — fail fast.
                    if (!it.isDirectory) throw IOException("mkdirs failed for $targetDir")
                }
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
            failed.remove(id)
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
                    // Adoption is atomic w.r.t. cancel() via [adoptionLock]: re-check the
                    // latch under the lock right before writing the handle, so a cancel
                    // landing mid-adoption either precedes the check (abort path below)
                    // or finds the handle already set (direct remove) — never the
                    // in-between that used to leave a cancelled torrent downloading.
                    val cancelled = synchronized(adoptionLock) {
                        val a = active[id]
                        if (a == null || a.wantCancelled) {
                            true
                        } else {
                            a.handle = h
                            // Add-window latches: a [setWantedFiles] that landed while the handle
                            // was still missing re-points the fresh selection first, then a latched
                            // pause applies (mirrors the caller issuing addFiles/pause right after
                            // start()). pendingWanted arms the same stale-finished guard as the live
                            // [setWantedFiles] path, and clears Completed so the enlarged selection
                            // can fire a fresh one.
                            a.pendingWanted?.let {
                                completed.remove(id)
                                applyPriorities(h, info, it)
                                pendingWanted[id] = System.currentTimeMillis()
                            }
                            if (a.wantPaused) runCatching { h.pause() }
                            false
                        }
                    }
                    if (cancelled) {
                        // cancel() latched during the add window: drop the just-adopted
                        // torrent silently — no events for a cancelled id.
                        runCatching { session.remove(h) }
                        active.remove(id)
                        return@launch
                    }
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
            val adopted = active[id]
            if (adopted != null && adopted.handle == null) {
                active.remove(id)
                // A latched cancel means the id is dead by user intent — no Failed event.
                if (!adopted.wantCancelled) {
                    _events.tryEmit(EngineEvent.Failed(id, "torrent handle not added within timeout"))
                }
            }
        }
    }

    /**
     * Pauses the torrent for [id]. In the add window (handle not yet adopted) the intent
     * latches on the [Active] entry and the find loop in [start] applies it on adoption.
     */
    override fun pause(id: String) {
        val a = active[id] ?: return
        val handle = a.handle
        if (handle == null) {
            a.wantPaused = true
            return
        }
        runCatching { handle.pause() }
    }

    /** Resume — also clears a pause latched during the add window. */
    override fun resume(id: String) {
        val a = active[id] ?: return
        val handle = a.handle
        if (handle == null) {
            a.wantPaused = false
            return
        }
        runCatching { handle.resume() }
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
        val handle = a.handle
        if (handle == null) {
            // Add window: latch the selection — the find loop in [start] applies it when
            // the handle is adopted (priorities first, then a latched pause).
            a.pendingWanted = wantedFiles
            return
        }
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

    /**
     * Cancels [id]. In the add window (handle not yet adopted) the entry is KEPT with
     * `wantCancelled` latched and — under [adoptionLock], the lock the find loop in
     * [start] adopts under — any torrent already sitting in the session for this id is
     * removed immediately: the session thread may have added it while the find loop has
     * not adopted the handle yet, and removing it here (instead of waiting for the loop)
     * tears the torrent down at cancel time. The loop's abort path re-fires a harmless
     * idempotent remove if it still finds one; before that latch existed, a cancel
     * racing the add window left the entry behind: the adopted torrent kept downloading
     * and kept emitting events for a cancelled id.
     */
    override fun cancel(id: String) {
        announceJobs.remove(id)?.cancel()
        pendingWanted.remove(id)
        synchronized(adoptionLock) {
            val a = active[id] ?: return
            val handle = a.handle
            if (handle == null) {
                a.wantCancelled = true
                val stray = runCatching { session.find(a.info.infoHashV1()) }.getOrNull()
                if (stray != null) runCatching { session.remove(stray) }
                return
            }
            active.remove(id)
            runCatching { session.remove(handle) }
        }
    }

    override fun close() {
        scope.cancel()
        if (announceHttp.isInitialized()) runCatching { announceHttp.value.close() }
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
