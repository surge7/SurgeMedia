package com.surgemusic.core.engine

import kotlinx.coroutines.flow.Flow

sealed interface EngineEvent {
    data class Progress(val bytesDone: Long, val bytesTotal: Long, val downloadSpeed: Long, val peers: Int) : EngineEvent
    data object Completed : EngineEvent
    data class Failed(val message: String) : EngineEvent
}

interface TorrentEngine {
    fun download(torrentBytes: ByteArray, targetDir: String, extraPeers: List<Pair<String, Int>> = emptyList()): Flow<EngineEvent>
    fun listenPort(): Int
    fun close()
}
