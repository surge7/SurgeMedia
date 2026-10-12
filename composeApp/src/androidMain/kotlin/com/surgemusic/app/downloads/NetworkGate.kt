package com.surgemusic.app.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadState

/**
 * Wi-Fi gate (plan Task 7): reports unmetered-connectivity changes and remembers which
 * downloads *it* paused, so only gate-paused items are resumed when Wi-Fi is back —
 * a manual pause survives network changes. `DownloadItem` gains no `autoPaused` field;
 * the ids live here ("acceptable v1" per plan). [items] snapshots the queue before pausing.
 */
class NetworkGate(
    private val context: Context,
    private val items: () -> List<DownloadItem>,
) {
    private val lock = Any()
    private val autoPausedIds = mutableSetOf<String>()

    /**
     * Registers a default-network callback. The first `onCapabilitiesChanged` arrives right
     * after registration, so the current unmetered state is delivered without an extra query.
     */
    fun register(onUnmeteredChanged: (Boolean) -> Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    onUnmeteredChanged(
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    )
                }

                override fun onLost(network: Network) {
                    onUnmeteredChanged(false)
                }
            },
        )
    }

    /**
     * Connection lost its unmetered capability while Wi-Fi-only is on: snapshot the ids
     * currently DOWNLOADING, delegate the actual pause to [pauseAll] (usually
     * [com.surgemusic.core.downloads.DownloadManager.pauseAll]) and remember them
     * for [resumeAutoPaused]. Returns the ids that were auto-paused.
     */
    fun pauseAndTrack(pauseAll: () -> Unit): List<String> =
        synchronized(lock) {
            val ids = items().filter { it.state == DownloadState.DOWNLOADING }.map { it.request.id }
            autoPausedIds.addAll(ids)
            pauseAll()
            ids
        }

    /**
     * Unmetered connectivity is back: resume exactly the ids this gate auto-paused
     * (the manager's own `resume` guard makes this a no-op for anything since cancelled).
     * Returns the ids that were resumed.
     */
    fun resumeAutoPaused(resume: (String) -> Unit): List<String> =
        synchronized(lock) {
            val ids = autoPausedIds.toList()
            autoPausedIds.clear()
            ids.forEach(resume)
            ids
        }
}
