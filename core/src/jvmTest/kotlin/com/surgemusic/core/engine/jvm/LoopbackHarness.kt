package com.surgemusic.core.engine.jvm

import com.surgemusic.core.engine.EngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeBytes

class LoopbackHarness : AutoCloseable {

    private val seedEngine = JLibTorrentEngine()
    private val leechEngine = JLibTorrentEngine()
    val sourceDir: Path = Files.createTempDirectory("surgemusic-seed")
    val targetDir: Path = Files.createTempDirectory("surgemusic-leech")
    private val torrentBytes: ByteArray

    init {
        (sourceDir / "track01.mp3").writeBytes(ByteArray(700_000) { (it * 31 + 7).toByte() })
        (sourceDir / "track02.mp3").writeBytes(ByteArray(300_000) { (it * 17 + 3).toByte() })
        torrentBytes = TestTorrentFactory.create(sourceDir, listOf("track01.mp3", "track02.mp3"))
        // Seed: adding the torrent where complete files already exist turns the handle into a seeder.
        // jlibtorrent nests a multi-file torrent under <savePath>/<torrent-name>/; the torrent's
        // name is sourceDir.fileName, so the save path for data already sitting directly in
        // sourceDir is its parent (sourceDir.parent/<name> === sourceDir).
        runBlocking {
            withTimeout(60_000) {
                seedEngine.download(torrentBytes, sourceDir.parent.toAbsolutePath().toString()).collect { }
            }
            // wait for the seed listen port to bind
            withTimeout(15_000) {
                while (seedEngine.listenPort() == 0) Thread.sleep(200)
            }
        }
    }

    fun leecherDownload(): Flow<EngineEvent> =
        leechEngine.download(
            torrentBytes,
            targetDir.toAbsolutePath().toString(),
            extraPeers = listOf("127.0.0.1" to seedEngine.listenPort()),
        )

    override fun close() {
        seedEngine.close()
        leechEngine.close()
        sourceDir.toFile().deleteRecursively()
        targetDir.toFile().deleteRecursively()
    }
}
