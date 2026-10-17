package com.surgemusic.app.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.surgemusic.app.AppContainer
import com.surgemusic.app.R
import com.surgemusic.core.downloads.DownloadItem
import com.surgemusic.core.downloads.DownloadState
import com.surgemusic.core.model.SizeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service keeping active downloads alive (plan Task 7).
 *
 * [onStartCommand] calls `startForeground` FIRST (the 5-second rule) — before anything that
 * could fail — then the service observes `AppContainer.downloadManager.items` and mirrors it
 * into one ongoing notification (title, "N active · speed/s · M queued", indeterminate
 * progress, single "Pause all" action routed back to this service as [ACTION_PAUSE_ALL]).
 * It stops itself (with `stopForeground`) once nothing is QUEUED/DOWNLOADING/FINALIZING/PAUSED.
 */
class DownloadService : Service() {

    // Default dispatcher: touching `AppContainer.downloadManager` lazily boots the native
    // torrent session (JLibTorrentEngine) — that must never run on the main thread
    // (onStartCommand is Main; a native session start there freezes the UI at every
    // app resume). NotificationManager.notify is thread-safe off-main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // START_STICKY may resurrect the service in a fresh process where no Activity has run;
        // AppContainer must be usable on its own.
        AppContainer.appContext = applicationContext
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5-second rule: go foreground immediately, before anything else can fail.
        // The initial notification is built from an EMPTY item list on purpose — reading
        // the real list would synchronously boot the torrent engine on Main (see scope).
        // The observer below replaces it with live numbers within one emission.
        // 3-arg overload (API 29+) pins the manifest-declared `dataSync` type explicitly
        // (required shape on targetSdk 34+).
        startForeground(
            NOTIFICATION_ID,
            buildNotification(emptyList()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        observeItems()
        if (intent?.action == ACTION_PAUSE_ALL) {
            scope.launch { AppContainer.downloadManager.pauseAll() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeItems() {
        if (observer?.isActive == true) return // onStartCommand can fire repeatedly; one collector
        observer = scope.launch {
            AppContainer.downloadManager.items.collect { items ->
                if (items.none { it.state in ACTIVE_STATES }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(items))
                }
            }
        }
    }

    private fun buildNotification(items: List<DownloadItem>): Notification {
        val downloading = items.filter { it.state == DownloadState.DOWNLOADING }
        val text = listOf(
            "${downloading.size} active",
            "${SizeParser.formatBytes(downloading.sumOf { it.downloadSpeed })}/s",
            "${items.count { it.state == DownloadState.QUEUED }} queued",
        ).joinToString(" · ")
        val pauseAllIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_downloads)
            .setContentTitle(getString(R.string.notification_title_downloads))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true) // indeterminate per plan
            .addAction(0, getString(R.string.notification_action_pause_all), pauseAllIntent)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1
        const val ACTION_PAUSE_ALL = "com.surgemusic.app.downloads.PAUSE_ALL"
        val ACTIVE_STATES = setOf(
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.FINALIZING,
            DownloadState.PAUSED,
        )
    }
}
