package com.surgemusic.core.engine.jvm

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeBytes

class LoopbackHarness : AutoCloseable {

    val seedEngine = JLibTorrentEngine()
    val leechEngine = JLibTorrentEngine()
    val sourceDir: Path = Files.createTempDirectory("surgemusic-seed")
    val targetDir: Path = Files.createTempDirectory("surgemusic-leech")
    private val torrentBytes: ByteArray

    init {
        (sourceDir / "track01.mp3").writeBytes(ByteArray(700_000) { (it * 31 + 7).toByte() })
        (sourceDir / "track02.mp3").writeBytes(ByteArray(300_000) { (it * 17 + 3).toByte() })
        torrentBytes = TestTorrentFactory.create(sourceDir, listOf("track01.mp3", "track02.mp3"))
        // Wait for the seed session's listen port to bind so startLeech()'s extraPeers
        // can target it. (Binding happens asynchronously after session.start().)
        runBlocking {
            withTimeout(15_000) {
                while (seedEngine.listenPort() == 0) delay(200)
            }
        }
    }

    fun startSeed() = seedEngine.start(
        SEED_ID, torrentBytes,
        // jlibtorrent nests a multi-file torrent under <savePath>/<torrent-name>/; the torrent's
        // name is sourceDir.fileName, so the save path for data already sitting directly in
        // sourceDir is its parent (sourceDir.parent/<name> === sourceDir).
        sourceDir.parent.toAbsolutePath().toString(),
    )

    fun startLeech() = leechEngine.start(
        LEECH_ID, torrentBytes, targetDir.toAbsolutePath().toString(),
        extraPeers = listOf("127.0.0.1" to seedEngine.listenPort()),
    )

    override fun close() {
        seedEngine.close()
        leechEngine.close()
        sourceDir.toFile().deleteRecursively()
        targetDir.toFile().deleteRecursively()
    }

    companion object {
        const val SEED_ID = "seed"
        const val LEECH_ID = "leech"
    }
}
