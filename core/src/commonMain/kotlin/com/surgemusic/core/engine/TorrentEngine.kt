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

/**
 * One file inside a torrent, parsed from its metadata. [index] is the engine's file
 * index — the key [TorrentEngine.start]'s `wantedFiles` selection uses; [path] is the
 * torrent-internal path (`Album/CD1/01.flac`).
 */
data class TorrentFileInfo(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
)

interface TorrentEngine : AutoCloseable {
    /**
     * Starts downloading [torrentBytes] into [targetDir]. [wantedFiles] selects the file
     * indices to download (see [TorrentFileInfo.index]); an empty set means every file.
     * Unselected files get skip priority — they are neither downloaded nor allocated.
     *
     * Restart semantics: a torrent already in the session for the same id/info-hash is
     * removed first, so a repeated [start] for an id is a fresh round wanting only
     * [wantedFiles] (previously downloaded files are not re-fetched — they are simply
     * not wanted).
     */
    fun start(id: String, torrentBytes: ByteArray, targetDir: String, wantedFiles: Set<Int> = emptySet())

    /** Parses the file list of [torrentBytes] (pad files excluded); throws on invalid bytes. */
    fun parseFiles(torrentBytes: ByteArray): List<TorrentFileInfo>

    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)   // removes the torrent; no further events for id

    /**
     * Changes which files the live torrent [id] downloads (same semantics as [start]'s
     * `wantedFiles`: empty set = every file, the rest get skip priority). Previously
     * downloaded files are never re-fetched; a torrent that had completed starts
     * emitting progress again once the new files finish. No-op when [id] is not in the
     * session.
     */
    fun setWantedFiles(id: String, wantedFiles: Set<Int>)

    val events: SharedFlow<EngineEvent>
    fun snapshot(id: String): EngineSnapshot?
    fun listenPort(): Int
}
