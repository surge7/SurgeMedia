package com.surgemusic.core.downloads

import kotlinx.serialization.Serializable

/**
 * One persisted queue entry — the snapshot [QueuePersistence] loads and saves. Only
 * non-terminal items are persisted (DONE/FAILED/CANCELLED need no resume), so every
 * restored entry re-enters the queue as QUEUED.
 *
 * `torrentBytesBase64` carries the .torrent bytes Base64-encoded (JSON-hostile bytes);
 * a null drops the entry on restore — the engine cannot restart without the torrent.
 * `wantedFiles` is the staged selection (empty list = every file, engine semantics).
 * `sizeBytes` is intentionally absent: it is selection-derived bookkeeping the engine's
 * Progress events refresh anyway, so restored items start from 0.
 */
@Serializable
data class PersistedDownload(
    val id: String,
    val topicId: Long,
    val title: String,
    val forumName: String,
    val torrentBytesBase64: String?,
    val stagingPath: String,
    val wantedFiles: List<Int>,
)

/**
 * Queue store behind DownloadManager's crash/exit recovery. Platform implementations
 * persist the snapshot to one JSON file (`~/.surgemusic/queue.json` on desktop,
 * `filesDir/queue.json` on android); a missing or corrupt store loads as empty. Load
 * also prunes dead entries: ones whose staging path vanished, plus orphaned staging
 * dirs nothing references anymore.
 */
interface QueuePersistence {
    /** Reads the persisted queue; empty (never throws) when the store is missing or corrupt. */
    suspend fun load(): List<PersistedDownload>

    /** Replaces the persisted queue with [items] (best-effort; failures are swallowed). */
    suspend fun save(items: List<PersistedDownload>)
}
