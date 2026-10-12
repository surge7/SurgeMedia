package com.surgemusic.core.engine.jvm

import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
import com.frostwire.jlibtorrent.swig.address
import com.frostwire.jlibtorrent.swig.error_code
import com.frostwire.jlibtorrent.swig.tcp_endpoint
import com.surgemusic.core.engine.EngineEvent
import com.surgemusic.core.engine.TorrentEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class JLibTorrentEngine : TorrentEngine {

    private val session = SessionManager()

    init { session.start() }

    // jlibtorrent 2.0.12.9: no SessionManager.listenPort(); the swig session_handle
    // exposes listen_port().
    override fun listenPort(): Int = session.swig().listen_port()

    override fun download(
        torrentBytes: ByteArray,
        targetDir: String,
        extraPeers: List<Pair<String, Int>>,
    ): Flow<EngineEvent> = flow {
        val info = TorrentInfo(torrentBytes)
        val dir = File(targetDir).apply { mkdirs() }
        session.download(info, dir)

        val handle = withTimeoutOrNull(20_000) {
            while (true) {
                val h = session.find(info.infoHashV1())
                if (h != null) return@withTimeoutOrNull h
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: run {
            emit(EngineEvent.Failed("torrent handle not added within timeout"))
            return@flow
        }

        extraPeers.forEach { (ip, port) -> addPeer(handle, ip, port) }

        while (true) {
            val st: TorrentStatus = handle.status()
            if (isFinished(st)) {
                emit(EngineEvent.Completed)
                return@flow
            }
            emit(
                EngineEvent.Progress(
                    bytesDone = st.totalWantedDone(),
                    bytesTotal = st.totalWanted(),
                    downloadSpeed = st.downloadPayloadRate().toLong(),
                    peers = st.numPeers(),
                )
            )
            delay(1000)
        }
    }

    /**
     * jlibtorrent 2.0.12.9 has no `TorrentHandle.addPeer(String, Int)` wrapper;
     * the equivalent is the swig-level `torrent_handle.connect_peer(tcp_endpoint)`.
     */
    private fun addPeer(handle: com.frostwire.jlibtorrent.TorrentHandle, ip: String, port: Int) {
        val ec = error_code()
        val addr = address.from_string(ip, ec)
        if (ec.value() == 0) handle.swig().connect_peer(tcp_endpoint(addr, port))
    }

    // jlibtorrent 2.0.12.9: the flags-object spelling `flags().is_finished()` does not
    // exist on `torrent_flags_t`; the wrapper `TorrentStatus.isFinished()` does. The
    // progress() >= 1.0f fallback covers rare float-rounding cases at 100%.
    private fun isFinished(st: TorrentStatus): Boolean =
        runCatching { st.isFinished() }.getOrDefault(false) || st.progress() >= 1.0f

    override fun close() = session.stop()
}
