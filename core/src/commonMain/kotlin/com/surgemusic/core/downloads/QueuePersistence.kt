package com.surgemusic.core.downloads

import kotlinx.serialization.Serializable

/**
 * One persisted queue entry — the snapshot [QueuePersistence] loads and saves. Two kinds
 * of entries share the list:
 * - non-terminal items (the resumable queue): every restored entry re-enters the queue
 *   as QUEUED and restarts on the engine;
 * - terminal items ([DownloadState.DONE]/[DownloadState.FAILED]/[DownloadState.CANCELLED]):
 *   bounded history (the manager keeps a capped newest window in completion order) —
 *   restored verbatim as history-only items, with no engine/staging revival.
 *
 * `torrentBytesBase64` carries the .torrent bytes Base64-encoded (JSON-hostile bytes);
 * a null drops a non-terminal entry on restore — the engine cannot restart without the
 * torrent. Terminal entries always persist null: they are never revived.
 * `wantedFiles` is the staged selection (empty list = every file, engine semantics).
 * `bytesTotal` lets restored items show their real size before the first engine Progress
 * tick (it stays selection-derived bookkeeping — Progress refreshes it anyway).
 * `note` carries a terminal item's [DownloadItem.outputNote] (DONE) or [DownloadItem.error].
 *
 * Defaults on the trailing fields keep pre-history files (which persisted only the
 * active queue, no state/size/note) loadable as-is — they restore as QUEUED.
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
    val state: String = DownloadState.QUEUED.name,
    val bytesTotal: Long = 0,
    val note: String? = null,
) {
    /** True for DONE/FAILED/CANCELLED entries — restored as history-only, never revived
     *  on the engine, and exempt from the stores' staging-path prune (their staging is
     *  legitimately deleted after finalize). */
    val isTerminal: Boolean
        get() = state == DownloadState.DONE.name ||
            state == DownloadState.FAILED.name ||
            state == DownloadState.CANCELLED.name
}

/**
 * Queue store behind DownloadManager's crash/exit recovery. Platform implementations
 * persist the snapshot to one JSON file (`~/.surgemusic/queue.json` on desktop,
 * `filesDir/queue.json` on android); a missing or corrupt store loads as empty. Load
 * also prunes dead entries: non-terminal ones whose staging path vanished (their torrent
 * cannot resume), plus orphaned staging dirs nothing non-terminal references anymore.
 * Terminal ([PersistedDownload.isTerminal]) entries are exempt on both counts — their
 * staging is legitimately deleted after finalize, and they must survive the prune to
 * keep the history.
 */
interface QueuePersistence {
    /** Reads the persisted queue; empty (never throws) when the store is missing or corrupt. */
    suspend fun load(): List<PersistedDownload>

    /** Replaces the persisted queue with [items] (best-effort; failures are swallowed). */
    suspend fun save(items: List<PersistedDownload>)
}
