package com.surgemusic.core.engine

import kotlinx.coroutines.flow.SharedFlow

enum class EngineState { DOWNLOADING, PAUSED, DONE, FAILED }

data class EngineSnapshot(
    val id: String, val state: EngineState,
    val bytesDone: Long, val bytesTotal: Long,
    val downloadSpeed: Long, val peers: Int,
)

sealed interface EngineEvent {
    data class Added(val id: String) : EngineEvent
    data class Progress(val id: String, val bytesDone: Long, val bytesTotal: Long, val downloadSpeed: Long, val peers: Int) : EngineEvent
    data class Completed(val id: String) : EngineEvent
    data class Failed(val id: String, val message: String) : EngineEvent
}

interface TorrentEngine : AutoCloseable {
    fun start(id: String, torrentBytes: ByteArray, targetDir: String)
    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)   // removes the torrent; no further events for id
    val events: SharedFlow<EngineEvent>
    fun snapshot(id: String): EngineSnapshot?
    fun listenPort(): Int
}
